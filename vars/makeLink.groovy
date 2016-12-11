import hudson.console.ModelHyperlinkNote
import hudson.console.HyperlinkNote

/*
 * Return a hyperlink string for the console output.
 */
String call(Object target, String text = '') {
    if (target instanceof String)
        HyperlinkNote.encodeTo(target, text ?: target)
    else if (text)
        ModelHyperlinkNote.encodeTo(target, text)
    else
        ModelHyperlinkNote.encodeTo(target)
}
