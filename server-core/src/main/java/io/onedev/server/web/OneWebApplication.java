package io.onedev.server.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.wicket.Application;
import org.apache.wicket.Component;
import org.apache.wicket.DefaultExceptionMapper;
import org.apache.wicket.IRequestCycleProvider;
import org.apache.wicket.Page;
import org.apache.wicket.RuntimeConfigurationType;
import org.apache.wicket.Session;
import org.apache.wicket.ajax.AbstractDefaultAjaxBehavior;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.AjaxRequestTarget.IJavaScriptResponse;
import org.apache.wicket.ajax.attributes.AjaxRequestAttributes;
import org.apache.wicket.application.IComponentInstantiationListener;
import org.apache.wicket.core.request.handler.ComponentNotFoundException;
import org.apache.wicket.core.request.handler.EmptyAjaxRequestHandler;
import org.apache.wicket.core.request.handler.ListenerInvocationNotAllowedException;
import org.apache.wicket.core.request.handler.PageProvider;
import org.apache.wicket.core.request.handler.RenderPageRequestHandler;
import org.apache.wicket.core.request.mapper.HomePageMapper;
import org.apache.wicket.markup.html.pages.AbstractErrorPage;
import org.apache.wicket.markup.html.pages.BrowserInfoPage;
import org.apache.wicket.protocol.http.WebApplication;
import org.apache.wicket.protocol.http.servlet.ServletWebRequest;
import org.apache.wicket.protocol.ws.WebSocketSettings;
import org.apache.wicket.protocol.ws.api.IWebSocketConnection;
import org.apache.wicket.protocol.ws.api.WebSocketResponse;
import org.apache.wicket.request.IExceptionMapper;
import org.apache.wicket.request.IRequestHandler;
import org.apache.wicket.request.IRequestMapper;
import org.apache.wicket.request.Request;
import org.apache.wicket.request.Response;
import org.apache.wicket.request.Url;
import org.apache.wicket.request.UrlRenderer;
import org.apache.wicket.request.cycle.RequestCycle;
import org.apache.wicket.request.cycle.RequestCycleContext;
import org.apache.wicket.request.handler.resource.ResourceReferenceRequestHandler;
import org.apache.wicket.request.http.WebRequest;
import org.apache.wicket.request.http.WebResponse;
import org.apache.wicket.request.mapper.info.PageComponentInfo;
import org.apache.wicket.util.IProvider;
import org.apache.wicket.util.time.Duration;

import de.agilecoders.wicket.core.settings.BootstrapSettings;
import io.onedev.commons.launcher.bootstrap.Bootstrap;
import io.onedev.commons.launcher.loader.AppLoader;
import io.onedev.server.OneDev;
import io.onedev.server.web.page.base.BasePage;
import io.onedev.server.web.page.error.ErrorPage;
import io.onedev.server.web.page.layout.UICustomization;
import io.onedev.server.web.resourcebundle.ResourceBundleReferences;
import io.onedev.server.web.util.AbsoluteUrlRenderer;
import io.onedev.server.web.websocket.WebSocketManager;

@Singleton
public class OneWebApplication extends WebApplication {
	
	private final Set<WebApplicationConfigurator> applicationConfigurators;
	
	private final UICustomization uiCustomization;
	
	@Inject
	public OneWebApplication(Set<WebApplicationConfigurator> applicationConfigurators, 
			UICustomization uiCustomization) {
		this.applicationConfigurators = applicationConfigurators;
		this.uiCustomization = uiCustomization;
	}
	
	@Override
	public RuntimeConfigurationType getConfigurationType() {
		if (Bootstrap.sandboxMode && !Bootstrap.prodMode)
			return RuntimeConfigurationType.DEVELOPMENT;
		else
			return RuntimeConfigurationType.DEPLOYMENT;
	}

	@Override
	protected void init() {
		super.init();

		getMarkupSettings().setDefaultMarkupEncoding("UTF-8");
		getMarkupSettings().setStripComments(true);
		getMarkupSettings().setStripWicketTags(true);
		
		getStoreSettings().setFileStoreFolder(Bootstrap.getTempDir());
		
		/*
		 * We disabled session store of pages to reduce memory usage at peak time. However when 
		 * an user visits the page, the page instance may not get written to disk timely due to 
		 * page synchronous writing. So adding a in-memory cache is important to compensate the 
		 * page written latency; otherwise, user may experience odd exceptions such as 
		 * ComponentNotFound when visit a page instance again after it is being created
		 */
		getStoreSettings().setInmemoryCacheSize(1000);
		
		getRequestCycleSettings().setTimeout(Duration.minutes(30));
		
		BootstrapSettings bootstrapSettings = new BootstrapSettings();
		bootstrapSettings.setAutoAppendResources(false);
		de.agilecoders.wicket.core.Bootstrap.install(this, bootstrapSettings);
		
		getComponentInstantiationListeners().add(new IComponentInstantiationListener() {
			
			@Override
			public void onInstantiation(Component component) {
				if ((component instanceof Page) 
						&& !(component instanceof AbstractErrorPage) 
						&& !(component instanceof BasePage)
						&& !(component instanceof BrowserInfoPage)) {
					throw new RuntimeException("Page classes should extend from BasePage.");
				}
			}
		});
		
		getAjaxRequestTargetListeners().add(new AjaxRequestTarget.IListener() {
			
			@Override
			public void onBeforeRespond(Map<String, Component> map, AjaxRequestTarget target) {
				BasePage page = (BasePage) target.getPage();
				if (page.getSessionFeedback().anyMessage())
					target.add(page.getSessionFeedback());
				
				for (Component component: map.values()) {
					target.prependJavaScript((String.format("$(document).trigger('beforeElementReplace', '%s');", component.getMarkupId())));
					target.appendJavaScript((String.format("$(document).trigger('afterElementReplace', '%s');", component.getMarkupId())));
				}
			}

			@Override
			public void onAfterRespond(Map<String, Component> map, IJavaScriptResponse response) {
				if (!map.isEmpty()) {
					AjaxRequestTarget target = RequestCycle.get().find(AjaxRequestTarget.class);
					if (target != null)
						OneDev.getInstance(WebSocketManager.class).observe((BasePage) target.getPage());
				}
			}

			@Override
			public void updateAjaxAttributes(AbstractDefaultAjaxBehavior behavior, AjaxRequestAttributes attributes) {
			}
			
		});

		WebSocketSettings.Holder.set(this, new WebSocketSettings() {

			@Override
			public WebResponse newWebSocketResponse(IWebSocketConnection connection) {
				return new WebSocketResponse(connection) {

					@Override
					public void sendError(int sc, String msg) {
						try {
							connection.sendMessage(WebSocketManager.ERROR_MESSAGE);
						} catch (IOException e) {
						}
					}

				};
			}
			
		});
		
		mount(new HomePageMapper(getHomePage()) {

			@Override
			protected void encodePageComponentInfo(Url url, PageComponentInfo info) {
				if (info.getComponentInfo() != null)
					super.encodePageComponentInfo(url, info);
			}
			
		});

		if (getConfigurationType() == RuntimeConfigurationType.DEPLOYMENT) {
			List<Class<?>> resourcePackScopes = new ArrayList<>();
			for (ResourcePackScopeContribution contribution: AppLoader.getExtensions(ResourcePackScopeContribution.class)) {
				resourcePackScopes.addAll(contribution.getResourcePackScopes());
			}
			new ResourceBundleReferences(resourcePackScopes.toArray(new Class<?>[0])).installInto(this);
		}
		
		setRequestCycleProvider(new IRequestCycleProvider() {

			@Override
			public RequestCycle get(RequestCycleContext context) {
				return new RequestCycle(context) {

					@Override
					protected UrlRenderer newUrlRenderer() {
						return new AbsoluteUrlRenderer(getRequest());
					}
					
				};
			}
			
		});
		
		mount(new OneUrlMapper(this));
		
		for (WebApplicationConfigurator configurator: applicationConfigurators)
			configurator.configure(this);
	}

	@Override
	public final IProvider<IExceptionMapper> getExceptionMapperProvider() {
		return new IProvider<IExceptionMapper>() {

			@Override
			public IExceptionMapper get() {
				return new DefaultExceptionMapper() {

					@Override
					protected IRequestHandler mapExpectedExceptions(Exception e, Application application) {
						RequestCycle requestCycle = RequestCycle.get();
						boolean isAjax = ((WebRequest)requestCycle.getRequest()).isAjax();
						if (isAjax && (e instanceof ListenerInvocationNotAllowedException || e instanceof ComponentNotFoundException))
							return EmptyAjaxRequestHandler.getInstance();
						
						IRequestMapper mapper = Application.get().getRootRequestMapper();
						if (mapper.mapRequest(requestCycle.getRequest()) instanceof ResourceReferenceRequestHandler)
							return new ResourceErrorRequestHandler(e);
						
						HttpServletResponse response = (HttpServletResponse) requestCycle.getResponse().getContainerResponse();
						if (!response.isCommitted())
							return createPageRequestHandler(new PageProvider(new ErrorPage(e)));
						
						return super.mapExpectedExceptions(e, application);
					}
					
				};
			}
			
		};
	}
	
	public static OneWebApplication get() {
		return (OneWebApplication) Application.get();
	}

	@Override
	public Class<? extends Page> getHomePage() {
		return uiCustomization.getHomePage();
	}

	@Override
	public Session newSession(Request request, Response response) {
		return new WebSession(request);
	}

	@Override
	public WebRequest newWebRequest(HttpServletRequest servletRequest, String filterPath) {
		return new ServletWebRequest(servletRequest, filterPath) {

			@Override
			public boolean shouldPreserveClientUrl() {
				if (RequestCycle.get().getActiveRequestHandler() instanceof RenderPageRequestHandler) {
					RenderPageRequestHandler requestHandler = 
							(RenderPageRequestHandler) RequestCycle.get().getActiveRequestHandler();
					
					/*
					 *  Add this to make sure that the page url does not change upon errors, so that 
					 *  user can know which page is actually causing the error. This behavior is common
					 *  for main stream applications.   
					 */
					if (requestHandler.getPage() instanceof ErrorPage) 
						return true;
				}
				return super.shouldPreserveClientUrl();
			}
			
		};
	}

	public Iterable<IRequestMapper> getRequestMappers() {
		return getRootRequestMapperAsCompound();
	}

}
