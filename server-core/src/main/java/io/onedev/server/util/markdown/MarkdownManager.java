package io.onedev.server.util.markdown;

import javax.annotation.Nullable;

import org.jsoup.nodes.Document;

import com.vladsch.flexmark.ast.Node;

import io.onedev.server.model.Project;

public interface MarkdownManager {
	
	/**
	 * Render specified markdown into html
	 * 
	 * @param markdown
	 * 			markdown to be rendered
	 * 			
	 * @return
	 * 			rendered html
	 */
	String render(String markdown);
	
	Node parse(String markdown);
	
	Document process(Document document, @Nullable Project project, @Nullable Object context, boolean forExternal);

	String process(String html, @Nullable Project project, @Nullable Object context, boolean forExternal);
	
	/**
	 * Escape html characters in specified markdown so that the markdown plain text 
	 * can be embedded in html content such as html email.
	 * 
	 * @param markdown
	 * 			markdown to be escaped
	 * @return
	 * 			escaped markdown plain text
	 */
	String escape(String markdown);
	
}
