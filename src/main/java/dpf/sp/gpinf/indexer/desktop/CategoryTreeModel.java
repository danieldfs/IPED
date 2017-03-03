package dpf.sp.gpinf.indexer.desktop;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;

public class CategoryTreeModel implements TreeModel {

  public static String rootName = "Categorias";
  private static String CONF_FILE = "conf/CategoryHierarchy.txt";

  public Category root;
  
  private List<TreeModelListener> listeners = new ArrayList<TreeModelListener>(); 
  
  private Collator collator;
  
  public static void install(){
	  if(App.get().categoryTree.getModel() instanceof CategoryTreeModel)
		  ((CategoryTreeModel)App.get().categoryTree.getModel()).updateCategories();
	  else
		  App.get().categoryTree.setModel(new CategoryTreeModel());
  }

  private CategoryTreeModel() {
    try {
      collator = Collator.getInstance();
      collator.setStrength(Collator.PRIMARY);
      this.root = loadHierarchy();
      
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
  
  private void updateCategories(){
	try {
		Category newRoot = loadHierarchy();
		addNewChildren(this.root, newRoot);
		
	} catch (IOException e) {
		e.printStackTrace();
	} 
  }

  class Category implements Comparable<Category> {

    String name;
    Category parent;
    TreeSet<Category> children = new TreeSet<Category>();

    private Category(String name, Category parent) {
      this.name = name;
      this.parent = parent;
    }

    public String toString() {
      return name;
    }

    @Override
    public int compareTo(Category o) {
      return collator.compare(name,o.name);
    }

    @Override
    public boolean equals(Object o) {
      return compareTo((Category)o) == 0;
    }

  }
  
  private String upperCaseChars(String cat){
	  StringBuilder str = new StringBuilder();
	  for(String s : cat.split(" "))
		  if(s.length() == 3)
			  str.append(s.toUpperCase() + " ");
		  else if(s.length() > 3)
			  str.append(s.substring(0, 1).toUpperCase() + s.substring(1) + " ");
		  else
			  str.append(s + " ");
	  return str.toString().trim();
  }

  private Category loadHierarchy() throws IOException {
	  
	Category root = new Category(rootName, null);
	  
	ArrayList<Category> categoryList = getLeafCategories(root);

    BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(
        new File(App.get().appCase.getAtomicSourceBySourceId(0).getModuleDir(), CONF_FILE)), "UTF-8"));

    String line = reader.readLine();
    while ((line = reader.readLine()) != null) {
      if (line.startsWith("#")) {
        continue;
      }
      String[] keyValuePair = line.split("=");
      if (keyValuePair.length == 2) {
    	Category category = new Category(keyValuePair[0].trim(), root);
    	category = tryAddAndGet(categoryList, category);
        String subcats = keyValuePair[1].trim();
        for (String subcat : subcats.split(";")) {
          Category sub = new Category(subcat.trim(), category);
          Category cat = tryAddAndGet(categoryList, sub);
          cat.parent = category;
        }
      }
    }
    reader.close();

    populateChildren(root, categoryList);
    
    filterEmptyCategories(root, getLeafCategories(root));
    
    return root;
  }
  
  private void addNewChildren(Category oldRoot, Category newRoot){
	  int idx = 0;
	  for(Category cat : newRoot.children){
		  if(!oldRoot.children.contains(cat)){
			  cat.parent = oldRoot;
			  oldRoot.children.add(cat);
			  notifyNewNode(cat, idx);
		  }else
			  addNewChildren(getFromSet(oldRoot.children, cat), cat);
		  idx++;
	  }
  }
  
  private Category getFromSet(Set<Category> set, Category cat){
	  Iterator<Category> it = set.iterator();
	  while(it.hasNext()){
		  Category next = it.next();
		  if(next.equals(cat))
			  return next;
	  }
	  return null;
  }
  
  private void notifyNewNode(Category cat, int idx){
	  int[] idxs = {idx};
	  Category[] cats = {cat};
	  LinkedList<Category> path = new LinkedList<Category>();
	  while(cat.parent != null)
		  path.addFirst(cat = cat.parent);
	  TreeModelEvent e = new TreeModelEvent(this, path.toArray(), idxs, cats);
	  for(TreeModelListener l : listeners)
		  l.treeNodesInserted(e);
  }
  
  private Category tryAddAndGet(ArrayList<Category> categoryList, Category category){
	  if (!categoryList.contains(category)){
          categoryList.add(category);
          return category;
      }else
	      return categoryList.get(categoryList.indexOf(category));
  }
  
  private ArrayList<Category> getLeafCategories(Category root){
	  ArrayList<Category> categoryList = new ArrayList<Category>();
	  for (String category : App.get().appCase.getCategories()) {
		  category = upperCaseChars(category);
	      categoryList.add(new Category(category, root));
	  }
	  return categoryList;
  }

  private void populateChildren(Category category, ArrayList<Category> categoryList) {
    for (Category cat : categoryList) {
      if (cat.parent.equals(category)) {
        category.children.add(cat);
        populateChildren(cat, categoryList);
      }
    }
  }

  private boolean filterEmptyCategories(Category category, ArrayList<Category> leafCategories) {
    boolean hasItems = false;
    if (leafCategories.contains(category)) {
      hasItems = true;
    }
	for (Category child : (TreeSet<Category>) category.children.clone()) {
      if (filterEmptyCategories(child, leafCategories)) {
        hasItems = true;
      }
    }
    if (!hasItems && category.parent != null) {
    	category.parent.children.remove(category);
    }
    return hasItems;
  }

  @Override
  public Object getRoot() {
    return root;
  }

  @Override
  public Object getChild(Object parent, int index) {
    return ((Category) parent).children.toArray()[index];
  }

  @Override
  public int getChildCount(Object parent) {
    return ((Category) parent).children.size();
  }

  @Override
  public boolean isLeaf(Object node) {
    return ((Category) node).children.size() == 0;
  }

  @Override
  public void valueForPathChanged(TreePath path, Object newValue) {
    // TODO Auto-generated method stub

  }

  @Override
  public int getIndexOfChild(Object parent, Object child) {
	if(parent == null || child == null)
	  return -1;
    int i = 0;
    for (Category cat : ((Category) parent).children) {
      if (cat.equals(child)) {
        return i;
      }
      i++;
    }
    return -1;
  }

  @Override
  public void addTreeModelListener(TreeModelListener l) {
    listeners.add(l);

  }

  @Override
  public void removeTreeModelListener(TreeModelListener l) {
	listeners.remove(l);

  }

}
