package io.onedev.server.web.page.project.pullrequests;

import javax.persistence.EntityNotFoundException;

import org.apache.wicket.Session;
import org.apache.wicket.markup.head.CssHeaderItem;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.html.link.Link;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.request.flow.RedirectToUrlException;
import org.apache.wicket.request.mapper.parameter.PageParameters;

import com.google.common.base.Preconditions;

import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.PullRequestManager;
import io.onedev.server.model.PullRequest;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.web.WebSession;
import io.onedev.server.web.page.project.ProjectPage;
import io.onedev.server.web.util.ConfirmClickModifier;

@SuppressWarnings("serial")
public class InvalidPullRequestPage extends ProjectPage {

	public static final String PARAM_REQUEST = "request";
	
	private IModel<PullRequest> requestModel;
	
	public InvalidPullRequestPage(PageParameters params) {
		super(params);
		
		requestModel = new LoadableDetachableModel<PullRequest>() {

			@Override
			protected PullRequest load() {
				Long requestNumber = params.get(PARAM_REQUEST).toLong();
				PullRequest request = OneDev.getInstance(PullRequestManager.class).find(getProject(), requestNumber);
				if (request == null)
					throw new EntityNotFoundException("Unable to find pull request #" + requestNumber + " in project " + getProject());
				Preconditions.checkState(!request.isValid());
				return request;
			}

		};
	}

	@Override
	protected void onInitialize() {
		super.onInitialize();
		add(new Link<Void>("delete") {

			@Override
			public void onClick() {
				PullRequest request = requestModel.getObject();
				OneDev.getInstance(PullRequestManager.class).delete(request);
				
				Session.get().success("Pull request #" + request.getNumber() + " deleted");
				
				String redirectUrlAfterDelete = WebSession.get().getRedirectUrlAfterDelete(PullRequest.class);
				if (redirectUrlAfterDelete != null)
					throw new RedirectToUrlException(redirectUrlAfterDelete);
				else
					setResponsePage(ProjectPullRequestsPage.class, ProjectPullRequestsPage.paramsOf(getProject()));
			}

			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(SecurityUtils.canManage(requestModel.getObject().getTargetProject()));
			}
			
		}.add(new ConfirmClickModifier("Do you really want to delete pull request #" + requestModel.getObject().getNumber() + "?")));
	}

	public static PageParameters paramsOf(PullRequest request) {
		PageParameters params = ProjectPage.paramsOf(request.getTarget().getProject());
		params.add(PARAM_REQUEST, request.getNumber());
		return params;
	}
	
	@Override
	protected void onDetach() {
		requestModel.detach();
		super.onDetach();
	}

	@Override
	protected boolean isPermitted() {
		return SecurityUtils.canReadCode(getProject());
	}
	
	@Override
	public void renderHead(IHeaderResponse response) {
		super.renderHead(response);
		response.render(CssHeaderItem.forReference(new ProjectPullRequestsCssResourceReference()));
	}

}
