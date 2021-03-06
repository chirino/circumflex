package ru.circumflex

import java.io._
import java.util.Random
import java.util.regex.Pattern
import collection.mutable.HashMap
import core._
import markeven._

/*!# The `markeven` Package

Package `markeven` contains a rendering method for Markeven Processor as well as
utility methods and constants used under the hood.

You should import this package to use Circumflex Markeven in your application:

    import ru.circumflex.markeven._

# Rendering methods

The `toHtml` method is used to perform text-to-html conversion using default `MarkevenProcessor`.
The usage is pretty simple:

    val text = """                                                {.scala}
    Hello world!              {#hi.greeting.example}
    ============

    This is a test.
    """
    val html = toHtml(text)

The example above yields following HTML:

    <h1 id="hi" class="greeting example">Hello world!</h1>        {.html}
    <p>This is a test.</p>

You can also use handy `renderToFile` method, which renders the contents of specified `src`
file into specified `dst` file:

    val src = new File("/path/to/my/text/file.txt")
    val dst = new File("/path/to/my/text/file.txt.html")
    markeven.renderToFile(src, dst)

It also performs last modified timestamps checking to avoid unnecessary transformation,
allowing effective caching of static content. If you do not need this caching behavior,
set the `force` parameter to `true`:

    markeven.renderToFile(src, dst, true)

You can use your custom `MarkevenProcessor` implementation with rendering methods: just set the
`markeven.processor` configuration parameter to fully-qualified name of your processor implementation.
*/
package object markeven {

  // Rendering stuff

  def processor = cx.instantiate[MarkevenProcessor]("markeven.processor", new MarkevenProcessor)

  def toHtml(source: CharSequence): String =
    processor.toHtml(source)

  def renderToFile(src: File, dst: File, force: Boolean = false): Unit =
    processor.renderToFile(src, dst, force)

  // Utilities

  val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
  val keySize = 20
  val rnd = new Random

  val escapeMap = Map[String, String](
    "\\\\" -> "&#92;",
    "\\`" -> "&#96;",
    "\\_" -> "&#95;",
    "\\*" -> "&#42;",
    "\\{" -> "&#123;",
    "\\}" -> "&#125;",
    "\\[" -> "&#91;",
    "\\]" -> "&#93;",
    "\\(" -> "&#40;",
    "\\)" -> "&#41;",
    "\\#" -> "&#35;",
    "\\+" -> "&#43;",
    "\\-" -> "&#45;",
    "\\~" -> "&#126;",
    "\\." -> "&#46;",
    "\\!" -> "&#33;")

  object regexes {
    val lineEnds = Pattern.compile("\\r\\n|\\r")
    val blankLines = Pattern.compile("^ +$", Pattern.MULTILINE)
    val blocks = Pattern.compile("\\n{2,}")
    val lines = Pattern.compile("\\n")
    val htmlNameExpr = "(?>[a-z][a-z0-9\\-_:.]*+\\b)"
    val inlineHtmlBlockStart = Pattern.compile("^ {0,3}<(" + htmlNameExpr + ")[\\S\\s]*?(/)?>",
      Pattern.CASE_INSENSITIVE | Pattern.MULTILINE)
    val inlineHtmlSpanStart = Pattern.compile("<(" + htmlNameExpr + ")[\\S\\s]*?(/)?>",
      Pattern.MULTILINE | Pattern.CASE_INSENSITIVE)
    val linkDefinition = Pattern.compile("^ {0,3}\\[(.+?)\\]: *(\\S.*?)" +
        "(\\n? *\"(.+?)\")?(?=\\n+|\\Z)", Pattern.MULTILINE)
    val blockSelector = Pattern.compile(" *+\\{(\\#[a-z0-9_-]+)?((?:\\.[a-z0-9_-]+)+)?\\}$",
      Pattern.CASE_INSENSITIVE | Pattern.MULTILINE)
    val tableCellSplit = Pattern.compile("\\|")
    val tableSeparatorLine = Pattern.compile("^[- :|]+$")
    val macro = Pattern.compile("\\[\\[((?>[a-zA-Z0-9_-]+:))?(.*?)\\]\\]", Pattern.DOTALL)
    val codeSpan = Pattern.compile("(`++)(.+?)\\1")
    val protectKey = Pattern.compile("!\\}[0-9a-zA-Z]{" + keySize + "}")
    val backslashChar = Pattern.compile("\\\\(\\S)")
    val refLinks = Pattern.compile("\\[([^\\[\\]]++)\\]\\[([^\\[\\]]*+)\\]")
    val inlineLinks = Pattern.compile("\\[([^\\[\\]]++)\\]\\((.*?)( +\"(.*?)\")?\\)")
    val htmlComment = Pattern.compile("^ {0,3}<!--.*?-->\\s*?(?=\\n+|\\Z)", Pattern.MULTILINE | Pattern.DOTALL)
    val spanEnhancements = Pattern.compile("([*_~])(?=\\S)(.+?)(?<=\\S)\\1")

    // escape patterns

    val e_amp = Pattern.compile("&(?!#?[xX]?[0-9a-zA-Z]+;)")
    val e_lt = Pattern.compile("<(?![a-z/?\\$!])")

    // deterministic patterns

    val d_code = Pattern.compile("(?: {4,}.*\\n?)+")
    val d_div = Pattern.compile("(?: {0,3}\\|.*\\n?)+")
    val d_blockquote = Pattern.compile("(?: {0,3}>.*\\n?)+")
    val d_hr = Pattern.compile("^-{3,} *\\n?$")
    val d_table = Pattern.compile("^-{3,}>?\\n.+\\n *-{3,}\\n?$", Pattern.DOTALL)
    val d_heading = Pattern.compile("^(\\#{1,6}) (.*) *\\#*$", Pattern.DOTALL)
    val d_h1 = Pattern.compile("^(.+)\\n=+\\n?$", Pattern.DOTALL)
    val d_h2 = Pattern.compile("^(.+)\\n-+\\n?$", Pattern.DOTALL)

    // trimming patterns

    val t_blockquote = Pattern.compile("^ *>", Pattern.MULTILINE)
    val t_div = Pattern.compile("^ *\\|", Pattern.MULTILINE)
    val t_ul = Pattern.compile("^(\\* +)")
    val t_ol = Pattern.compile("^(\\d+\\. +)")
    val t_tr = Pattern.compile("^\\|")

    // list split patterns

    val s_ul = Pattern.compile("\\n+(?=\\* )")
    val s_ol = Pattern.compile("\\n+(?=\\d+\\. )")

    // cache dynamic expressions

    protected val outdentMap = new HashMap[Int, Pattern]
    protected val placeholder = Pattern.compile("^", Pattern.MULTILINE)

    def outdent(level: Int): Pattern = outdentMap.get(level) match {
      case Some(p) => p
      case _ =>
        val p = Pattern.compile("^ {0," + level + "}", Pattern.MULTILINE)
        outdentMap += (level -> p)
        p
    }

    protected val htmlTagMap = new HashMap[String, Pattern]

    protected def htmlTagInternal(tag: String): Pattern = htmlTagMap.get(tag) match {
      case Some(p) => p
      case _ =>
        val p = Pattern.compile("(<" + tag + "\\b.*?(/)?>)|(</" + tag + "\\s*>)",
          Pattern.CASE_INSENSITIVE)
        htmlTagMap += tag -> p
        p
    }

    def htmlTag(tag: String): Pattern = htmlTagInternal(tag.toLowerCase)

    // typographic patterns

    val ty_dash = Pattern.compile("--")
    val ty_larr = Pattern.compile("&lt;-|<-")
    val ty_rarr = Pattern.compile("-&gt;|->")
    val ty_hellip = Pattern.compile("\\.{3}")
    val ty_trade = Pattern.compile("\\([tT][mM]\\)")
    val ty_reg = Pattern.compile("\\([rR]\\)")
    val ty_copy = Pattern.compile("\\([cC]\\)")
    val ty_ldquo = Pattern.compile("(?<=\\s|\\A)(?:\"|&quot;)(?=\\S)")
    val ty_rdquo = Pattern.compile("(?<=[\\p{L})?!.])(?:\"|&quot;)(?=[.,;?!*)]|\\s|\\Z)")
  }

  object typographics {
    val dash = cx.get("me.dash").map(_.toString).getOrElse("&mdash;")
    val larr = cx.get("me.larr").map(_.toString).getOrElse("&larr;")
    val rarr = cx.get("me.rarr").map(_.toString).getOrElse("&rarr;")
    val hellip = cx.get("me.hellip").map(_.toString).getOrElse("&hellip;")
    val trade = cx.get("me.trade").map(_.toString).getOrElse("&trade;")
    val reg = cx.get("me.reg").map(_.toString).getOrElse("&reg;")
    val copy = cx.get("me.copy").map(_.toString).getOrElse("&copy;")
    val ldquo = cx.get("me.ldquo").map(_.toString).getOrElse("&ldquo;")
    val rdquo = cx.get("me.rdquo").map(_.toString).getOrElse("&rdquo;")
  }
}