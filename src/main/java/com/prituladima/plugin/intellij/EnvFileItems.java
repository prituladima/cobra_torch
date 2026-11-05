package com.prituladima.plugin.intellij;

import com.intellij.openapi.editor.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The list items of a .conda_env.yml, read line by line rather than from the YAML tree, so that a file
 * being typed still works: {@code channels:} items, {@code dependencies:} items, and the pip items
 * nested under {@code - pip:}.
 */
final class EnvFileItems {
  /** {@code - name}, {@code - name=1.2}, {@code - "name>=1"}, {@code - channel::name}; group 1 is the name. */
  private static final Pattern ITEM = Pattern.compile("([ \\t]*)-[ \\t]+['\"]?(?:[\\w.-]+::)?([\\w.-]+)");
  private static final Pattern TOP_LEVEL_KEY = Pattern.compile("([\\w.-]+)[ \\t]*:");
  /** Pip extras such as {@code [cuda]} say which parts to install, not which version. */
  private static final Pattern EXTRAS = Pattern.compile("^\\[[^]]*]");

  /**
   * One item. {@code pinned}: something follows the name ({@code =1.2}, {@code >=2}, {@code @ url}, or the
   * {@code :} of {@code - pip:}), so the file itself says which version.
   */
  record Item(String section, boolean pip, String name, int nameStart, int nameEnd, boolean pinned) {}

  private EnvFileItems() {}

  static List<Item> parse(Document doc) {
    List<Item> items = new ArrayList<>();
    CharSequence text = doc.getCharsSequence();
    String section = "";
    int pipIndent = -1;   // indentation of the "- pip:" item while inside its list, else -1
    for (int line = 0; line < doc.getLineCount(); line++) {
      int start = doc.getLineStartOffset(line);
      String s = text.subSequence(start, doc.getLineEndOffset(line)).toString();
      if (!s.isEmpty() && " \t#-".indexOf(s.charAt(0)) < 0) {   // a top-level key starts a new section
        Matcher key = TOP_LEVEL_KEY.matcher(s);
        section = key.lookingAt() ? key.group(1) : "";
        pipIndent = -1;
        continue;
      }
      Matcher m = ITEM.matcher(s);
      if (!m.lookingAt()) continue;
      int indent = m.group(1).length();
      if (pipIndent >= 0 && indent <= pipIndent) pipIndent = -1;
      String rest = s.substring(m.end(2));
      boolean pinned = isPinned(rest);
      items.add(new Item(section, pipIndent >= 0, m.group(2), start + m.start(2), start + m.end(2), pinned));
      if ("dependencies".equals(section) && "pip".equals(m.group(2)) && rest.stripLeading().startsWith(":")) {
        pipIndent = indent;
      }
    }
    return items;
  }

  private static boolean isPinned(String rest) {
    int comment = rest.indexOf('#');
    String r = (comment >= 0 ? rest.substring(0, comment) : rest).strip();
    r = EXTRAS.matcher(r).replaceFirst("");
    r = r.replace("'", "").replace("\"", "").strip();
    return !r.isEmpty();
  }
}
