package io.onedev.server.web.component.sidebar;

import java.util.List;

import javax.annotation.Nullable;
import javax.servlet.http.Cookie;

import org.apache.wicket.Component;
import org.apache.wicket.behavior.AttributeAppender;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.head.JavaScriptHeaderItem;
import org.apache.wicket.markup.head.OnDomReadyHeaderItem;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.request.cycle.RequestCycle;
import org.apache.wicket.request.http.WebRequest;

import io.onedev.server.web.asset.icon.IconScope;
import io.onedev.server.web.component.svg.SpriteImage;
import io.onedev.server.web.component.tabbable.Tab;
import io.onedev.server.web.component.tabbable.Tabbable;
import io.onedev.server.web.util.WicketUtils;

@SuppressWarnings("serial")
public abstract class SideBar extends Panel {

	private final String miniCookieKey;
	
	public SideBar(String id, @Nullable String miniCookieKey) {
		super(id);
		this.miniCookieKey = miniCookieKey;
	}

	@Override
	protected void onInitialize() {
		super.onInitialize();
		
		add(newHead("head"));
		add(new Tabbable("tabs", newTabs()));
		
		String miniToggleContent = String.format("<svg class='icon'><use xlink:href='%s'/></svg>", 
				SpriteImage.getVersionedHref(IconScope.class, "arrow2"));
		
		add(new Label("miniToggle", miniToggleContent)
				.setEscapeModelStrings(false)
				.setVisible(miniCookieKey!=null));

		add(AttributeAppender.append("class", "sidebar"));
		
		if (miniCookieKey != null) {
			add(AttributeAppender.append("class", "minimizable"));
			WebRequest request = (WebRequest) RequestCycle.get().getRequest();
			Cookie miniCookie = request.getCookie(miniCookieKey);
			if (miniCookie != null) {
				if ("yes".equals(miniCookie.getValue()))
					add(AttributeAppender.append("class", "minimized"));
			} else if (WicketUtils.isDevice()) {
				add(AttributeAppender.append("class", "minimized"));
			}
		} 
	}
	
	protected Component newHead(String componentId) {
		return new WebMarkupContainer(componentId).setVisible(false);
	}
	
	protected abstract List<? extends Tab> newTabs();

	@Override
	public void renderHead(IHeaderResponse response) {
		super.renderHead(response);
		response.render(JavaScriptHeaderItem.forReference(new SidebarResourceReference()));
		String script = String.format("onedev.server.sidebar.onDomReady('%s', %s);", 
				getMarkupId(true), miniCookieKey!=null?"'"+miniCookieKey+"'":"undefined");
		response.render(OnDomReadyHeaderItem.forScript(script));
	}

}
