
import java.io.*;
import java.io.FilenameFilter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.json.*;

import org.asciidoctor.Asciidoctor.Factory;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.ast.DocumentHeader;


public class BuildNavAndPDFBody {

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new RuntimeException("Wrong # of args: " + args.length);
    }

    final File adocDir = new File(args[0]);
    final String mainPageShortname = args[1];
    if (! adocDir.exists()) {
      throw new RuntimeException("asciidoc directory does not exist: " + adocDir.toString());
    }

    // build up a quick mapping of every known page
    System.out.println("Building up tree of all known pages");
    final Map<String,Page> allPages = new LinkedHashMap<String,Page>();
    Asciidoctor doctor = null;
    try {
      doctor = Factory.create();
      final File[] adocFiles = adocDir.listFiles(ADOC_FILE_NAMES);
      for (File file : adocFiles) {
        Page page = new Page(file, doctor.readDocumentHeader(file));
        if (allPages.containsKey(page.shortname)) {
          throw new RuntimeException("multiple pages with same shortname: " + page.file.toString() + " and " + allPages.get(page.shortname));
        }
        allPages.put(page.shortname, page);
      }
    } finally {
      if (null != doctor) {
        doctor.shutdown();
        doctor = null;
      }
    }

    // build up a hierarchical structure rooted at our mainPage
    final Page mainPage = allPages.get(mainPageShortname);
    if (null == mainPage) {
      throw new RuntimeException("no main-page found with shortname: " + mainPageShortname);
    }
    mainPage.buildKidsRecursive(allPages);

    // TODO: use depthFirstWalk to prune allPages to validate that we don't have any loops or orphan pages


    // Build up the PDF file -- TODO: where should this live?
    File pdfFile = new File(new File(adocDir, "_data"), "pdf-main-body.adoc");
    if (pdfFile.exists()) {
      throw new RuntimeException(pdfFile.toString() + " already exists");
    }
    System.out.println("Creating " + pdfFile.toString());
    try (Writer w = new OutputStreamWriter(new FileOutputStream(pdfFile), "UTF-8")) {
      // Note: not worrying about headers or anything like that ...
      // expecting this file to just be included by the main PDF file.

      // track how deep we are so we can adjust headers accordingly
      // start with a "negative" depth to treat all "top level" pages as same depth as main-page using Math.max
      // (see below)
      final AtomicInteger depth = new AtomicInteger(-1);
      
      mainPage.depthFirstWalk(new Page.RecursiveAction() {
        public boolean act(Page page) {
          try {
            // HACK: where this file actually lives will determine what we need here...
            w.write("include::../");
            w.write(page.file.getName());
            w.write("[leveloffset=+"+Math.max(0, depth.intValue())+"]\n\n");
            depth.incrementAndGet();
            return true;
          } catch (IOException ioe) {
            throw new RuntimeException("IOE recursively acting on " + page.shortname, ioe);
          }
        }
        public void postKids(Page page) {
          depth.decrementAndGet();
        }
      });
    }
    
    // Build up the sidebar file for jekyll
    File sidebarFile = new File(new File(adocDir, "_data"), "sidebar.json");
    if (sidebarFile.exists()) {
      throw new RuntimeException(sidebarFile.toString() + " already exists");
    }
    System.out.println("Creating " + sidebarFile.toString());
    try (Writer w = new OutputStreamWriter(new FileOutputStream(sidebarFile), "UTF-8")) {
      final JSONArray folders = new JSONArray();
      final JSONObject sidebar = new JSONObject()
        .put("title","sidebar")
        .put("product","Solr Reference Guide")
        .put("version", 6.2)
        .put("folders", folders);

      // track how deep we are so we can adjust folder vs folderitem accordingly
      final AtomicInteger depth = new AtomicInteger(0);
      final AtomicReference<JSONArray> currentFolderitems = new AtomicReference<JSONArray>(null);
      
      mainPage.depthFirstWalk(new Page.RecursiveAction() {
        public boolean act(Page page) {

          // NOTE: current jekyll theme only supports 2 level sidebar, ...
          // and "folders" can't link to pages, just expand/collapse, ...
          // so for now: each level1 page is a folder, and it & all decendents are items in that folder
          //
          // TODO: can we improve the jekyll theme to supports something closer to confluence???
          
          // create a folder for every level 1 page
          if (1 == depth.intValue()) {
            JSONArray folderitems = new JSONArray();
            currentFolderitems.set(folderitems);
            folders.put(new JSONObject()
                        .put("title",page.title)
                        .put("output","pdf,web")
                        .put("folderitems", folderitems));
          }
          if (1 <= depth.intValue()) {
            // add every page with level >= 1 to the current folder
            JSONArray folderitems = currentFolderitems.get();
            folderitems.put(new JSONObject()
                            .put("title", page.title)
                            .put("output","pdf,web")
                            .put("url", page.permalink));
            
          }
          
          depth.incrementAndGet();
          return true;
        }
        public void postKids(Page page) {
          depth.decrementAndGet();
        }
      });

      (new JSONObject()).put("entries", (new JSONArray()).put(sidebar)).write(w);
    }
    
  }

  /** Simple struct for modeling the key metadata for dealing with page navigation */
  public static final class Page {
    public final File file;
    public final String title;
    public final String shortname;
    public final String permalink;
    public final List<String> kidShortnames;
    /** NOTE: not populated on construction
     * @see #buildKidsRecursive
     */
    public final List<Page> kids;
    private final List<Page> mutableKids;
    public Page(File file, DocumentHeader header) {
      this.file = file;
      this.title = header.getDocumentTitle().getMain();
      
      // TODO: do error checking if attribute metadata we care about is missing
      Map<String,Object> attrs = header.getAttributes();
      this.shortname = (String) attrs.get("page-shortname");
      this.permalink = (String) attrs.get("page-permalink");
      
      if (attrs.containsKey("page-children")) {
        String kidsString = ((String) attrs.get("page-children")).trim();
        this.kidShortnames = Collections.<String>unmodifiableList
          (Arrays.asList(kidsString.split(",\\s+")));
        this.mutableKids = new ArrayList<Page>(kidShortnames.size());
      } else {
        this.kidShortnames = Collections.<String>emptyList();
        this.mutableKids = Collections.<Page>emptyList();
      }
      this.kids = Collections.<Page>unmodifiableList(mutableKids);
    }

    /** Recursively populates {@link #kids} from {@link #kidShortnames} via the <code>allPages</code> Map */
    public void buildKidsRecursive(Map<String,Page> allPages) {
      for (String kidShortname : kidShortnames) {
        Page kid = allPages.get(kidShortname);
        if (null == kid) {
          throw new RuntimeException("Unable to locate " + kidShortname + "; child of " + shortname + "("+file.toString());
        }
        mutableKids.add(kid);
        kid.buildKidsRecursive(allPages);
      }
    }

    /** 
     * Do a depth first recursive action on this node and it's {@link #kids} 
     * @see RecursiveAction
     */
    public void depthFirstWalk(RecursiveAction action) {
      if (action.act(this)) {
        for (Page kid : kids) {
          kid.depthFirstWalk(action);
        }
        action.postKids(this);
      }
    }

    /** @see #depthFirstWalk */
    public static interface RecursiveAction {
      /** return true if kids should also be visited */
      public boolean act(Page page);
      /** 
       * called after recusion to each kid (if any) of specified node, 
       * never called if {@link #act} returned false 
       */
      public default void postKids(Page page) { /* No-op */ };
    }
  }

  
  /** Trivial filter for only "*.adoc" files */
  public static final FilenameFilter ADOC_FILE_NAMES = new FilenameFilter() {
    public boolean accept(File dir, String name) {
      return name.endsWith(".adoc");
    }
  };
}
