package io.onedev.server.web.page.layout;

import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.panel.Panel;

@SuppressWarnings("serial")
abstract class CloseablePanel extends Panel {

	private final String title;
	
	public CloseablePanel(String id, String title) {
		super(id);
		this.title = title;
	}

	@Override
	protected void onInitialize() {
		super.onInitialize();
		add(new Label("title", title));
		add(renderSubHeader("subHeader"));
		add(new AjaxLink<Void>("close") {

			@Override
			public void onClick(AjaxRequestTarget target) {
				onClose(target);
			}
			
		});
		add(newFloatingContent("content"));
	}

	protected Component renderSubHeader(String componentId) {
		return new WebMarkupContainer(componentId).setVisible(false);
	}
	
	protected abstract Component newFloatingContent(String componentId);
	
	protected abstract void onClose(AjaxRequestTarget target);
}
