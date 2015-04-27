/*
 * Copyright 2012-2014, Luis Filipe da Cruz Nassif
 * 
 * This file is part of Indexador e Processador de Evidências Digitais (IPED).
 *
 * IPED is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * IPED is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with IPED.  If not, see <http://www.gnu.org/licenses/>.
 */
package dpf.sp.gpinf.indexer.search;

import java.io.File;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.TreeMap;

import javax.swing.SwingUtilities;

import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.highlight.TextFragment;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TemporaryResources;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;

import dpf.sp.gpinf.indexer.Configuration;
import dpf.sp.gpinf.indexer.analysis.CategoryTokenizer;
import dpf.sp.gpinf.indexer.io.ParsingReader;
import dpf.sp.gpinf.indexer.parsers.IndexerDefaultParser;
import dpf.sp.gpinf.indexer.parsers.util.ItemInfo;
import dpf.sp.gpinf.indexer.process.IndexItem;
import dpf.sp.gpinf.indexer.process.task.ParsingTask;
import dpf.sp.gpinf.indexer.util.CancelableWorker;
import dpf.sp.gpinf.indexer.util.ProgressDialog;

public class TextParser extends CancelableWorker {

	private static TextParser parsingTask;
	private File file;
	private String contentType;
	volatile int id;
	private Document doc;
	protected ProgressDialog progressMonitor;

	private static Object lock = new Object();
	private TemporaryResources tmp;
	public static FileChannel parsedFile;
	public boolean firstHitAutoSelected = false;

	// contém offset, tamanho, viewRow inicial e viewRow final dos fragemtos com
	// sortedHits
	public TreeMap<Long, int[]> sortedHits = new TreeMap<Long, int[]>();

	// contém offset dos hits
	public ArrayList<Long> hits = new ArrayList<Long>();

	// contém offset das quebras de linha do preview
	public ArrayList<Long> viewRows = new ArrayList<Long>();

	public TextParser(Document doc, File file, String contentType, TemporaryResources tmp) {
		try {
			this.file = file;
			this.contentType = contentType;
			this.tmp = tmp;
			this.doc = doc;

			if (parsingTask != null) {
				parsingTask.cancel(false);
			}
			parsingTask = this;

			String idStr = doc.get(IndexItem.ID);
			if (idStr != null)
				id = Integer.parseInt(idStr);

			this.addPropertyChangeListener(new TextParserListener(this));

		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	@Override
	public void done() {

		App.get().tabbedHits.setTitleAt(0, hits.size() + " Ocorrências");
		// garante que dialog será fechado após ser criado
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				if (progressMonitor != null)
					progressMonitor.close();
			}
		});

	}

	@Override
	public Void doInBackground() {

		synchronized (lock) {

			if (this.isCancelled())
				return null;

			SwingUtilities.invokeLater(new Runnable() {
				public void run() {
					progressMonitor = new ProgressDialog(App.get(), parsingTask);
					if(App.get().textSizes.length > id)
						progressMonitor.setMaximum(App.get().textSizes[id] * 1000L);
				}
			});

			sortedHits = new TreeMap<Long, int[]>();
			hits = new ArrayList<Long>();
			viewRows = new ArrayList<Long>();
			App.get().hitsModel.fireTableDataChanged();
			App.get().textViewer.textViewerModel.fireTableDataChanged();

			parseText();
		}

		return null;
	}

	public void parseText() {
		ParsingReader textReader = null;
		try {

			Metadata metadata = new Metadata();
			metadata.set(IndexerDefaultParser.INDEXER_CONTENT_TYPE, contentType);
			if (Boolean.valueOf(doc.get(IndexItem.TIMEOUT)))
				metadata.set(IndexerDefaultParser.INDEXER_TIMEOUT, "true");

			TikaInputStream tis = TikaInputStream.get(file, metadata);
			metadata.set(Metadata.RESOURCE_NAME_KEY, doc.get(IndexItem.NAME));

			HashSet<String> categorias = new HashSet<String>();
			if (doc.get(IndexItem.CATEGORY) != null)
				for (String categoria : doc.get(IndexItem.CATEGORY).split("" + CategoryTokenizer.SEPARATOR))
					categorias.add(categoria);

			ParseContext context = new ParseContext();
			context.set(Parser.class, (Parser) App.get().autoParser);
			context.set(ItemInfo.class, new ItemInfo(id, categorias, doc.get(IndexItem.PATH), Boolean.getBoolean(doc.get(IndexItem.CARVED))));
			ParsingTask expander = new ParsingTask(context);
			expander.init(Configuration.properties, new File(Configuration.configPath));
			context.set(EmbeddedDocumentExtractor.class, expander);

			// Tratamento p/ acentos de subitens de ZIP
			ArchiveStreamFactory factory = new ArchiveStreamFactory();
			factory.setEntryEncoding("Cp850");
			context.set(ArchiveStreamFactory.class, factory);
			
			/*PDFParserConfig config = new PDFParserConfig();
			config.setExtractInlineImages(true);
			context.set(PDFParserConfig.class, config);
			*/

			textReader = new ParsingReader((Parser) App.get().autoParser, tis, metadata, context);

			tmp.dispose();
			File tmpFile = tmp.createTemporaryFile();
			parsedFile = new RandomAccessFile(tmpFile, "rw").getChannel();
			tmp.addResource(parsedFile);

			String contents, fieldName = "conteudo";
			int read = 0, lastRowInserted = -1;
			long totalRead = 0, lastNewLinePos = 0;
			boolean lineBreak = false;
			viewRows.add(0L);

			while (!this.isCancelled()) {
				if (read == -1)
					break;

				char[] buf = new char[App.TEXT_BREAK_SIZE];
				int off = 0;
				while (!this.isCancelled() && off != buf.length && (read = textReader.read(buf, off, buf.length - off)) != -1) {
					off += read;
					totalRead += read;
					this.firePropertyChange("progress", 0, totalRead);
				}

				if (this.isCancelled())
					break;

				contents = new String(buf, 0, off);

				// remove "vazio" do início do texto
				if (lastRowInserted == -1) {
					int lastIndex = contents.length() - 1;
					if (lastIndex > 0)
						contents = contents.substring(0, lastIndex).trim() + contents.charAt(lastIndex);
				}

				TextFragment[] fragments = TextHighlighter.getHighlightedFrags(lastRowInserted == -1, contents, fieldName, App.FRAG_SIZE);

				TreeMap<Integer, TextFragment> sortedFrags = new TreeMap<Integer, TextFragment>();
				for (int i = 0; i < fragments.length; i++)
					sortedFrags.put(fragments[i].getFragNum(), fragments[i]);

				if (this.isCancelled())
					break;

				// TODO reduzir código, caracteres nas bordas, codificação, nao
				// juntar linhas
				for (TextFragment frag : sortedFrags.values()) {

					// grava texto em disco
					String fragment = frag.toString();
					byte data[] = fragment.getBytes("windows-1252");
					long startPos = parsedFile.position();
					ByteBuffer out = ByteBuffer.wrap(data);
					while (out.hasRemaining())
						parsedFile.write(out);

					// adiciona linhas adicionais no viewer para cada \n dentro
					// do fragmento
					lineBreak = false;
					int startRow = viewRows.size() - 1;
					if (viewRows.size() - 1 < App.MAX_LINES) {
						for (int i = 0; i < data.length - 1; i++) {
							if (data[i] == 0x0A) {
								viewRows.add(startPos + i + 1);
								lineBreak = true;
								if (viewRows.size() - 1 == App.MAX_LINES)
									break;
								// lastNewLinePos = startPos + i;
							}
							/*
							 * else if((startPos + i) - lastNewLinePos >=
							 * App.MAX_LINE_SIZE){ int k = i; while(k >= 0 &&
							 * Character
							 * .isLetterOrDigit(fragment.codePointAt(k))) k--;
							 * lastNewLinePos = startPos + k;
							 * App.get().viewRows.add(lastNewLinePos + 1);
							 * if(App.get().viewRows.size() - 1 ==
							 * App.MAX_LINES) break; }
							 */
						}
					}

					// adiciona hit
					int numHits = hits.size();
					if (numHits < App.MAX_HITS && frag.getScore() > 0) {
						int[] hit = new int[3];
						hit[0] = data.length;
						hit[1] = startRow;
						hit[2] = viewRows.size() - 1;
						hits.add(startPos);
						sortedHits.put(startPos, hit);

						// atualiza viewer permitindo rolar para o hit
						if (viewRows.size() - 1 < App.MAX_LINES) {
							App.get().textViewer.textViewerModel.fireTableRowsInserted(lastRowInserted + 1, viewRows.size() - 2);
							lastRowInserted = viewRows.size() - 2;
						} else {
							int line = App.MAX_LINES + (int) ((parsedFile.size() - viewRows.get(App.MAX_LINES)) / App.MAX_LINE_SIZE);
							App.get().textViewer.textViewerModel.fireTableRowsInserted(lastRowInserted + 1, line);
							lastRowInserted = line;
						}

						// atualiza lista de hits
						App.get().hitsModel.fireTableRowsInserted(numHits, numHits);
						this.firePropertyChange("hits", numHits, numHits + 1);
					}

					// adiciona linha no viewer para o fragmento
					if (!lineBreak && viewRows.size() - 1 < App.MAX_LINES)
						viewRows.add(parsedFile.position());

				}
				// atualiza viewer
				if (viewRows.size() - 1 < App.MAX_LINES) {
					App.get().textViewer.textViewerModel.fireTableRowsInserted(lastRowInserted + 1, viewRows.size() - 2);
					lastRowInserted = viewRows.size() - 2;
				} else {
					int line = App.MAX_LINES + (int) ((parsedFile.size() - viewRows.get(App.MAX_LINES)) / App.MAX_LINE_SIZE);
					App.get().textViewer.textViewerModel.fireTableRowsInserted(lastRowInserted + 1, line);
					lastRowInserted = line;
				}
			}
			if (lineBreak && viewRows.size() - 1 < App.MAX_LINES) {
				viewRows.add(parsedFile.size());
				lastRowInserted++;
				App.get().textViewer.textViewerModel.fireTableRowsInserted(lastRowInserted, lastRowInserted);
			}

			textReader.reallyClose();

		} catch (InterruptedIOException e1) {
			e1.printStackTrace();
		} catch (Throwable e) {
			e.printStackTrace();
		}
		if (this.isCancelled() && textReader != null)
			textReader.closeAndInterruptParsingTask();

	}

}