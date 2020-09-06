package io.onedev.server.web.page.project.pullrequests.detail;

import static io.onedev.server.model.support.pullrequest.MergeStrategy.CREATE_MERGE_COMMIT;
import static io.onedev.server.model.support.pullrequest.MergeStrategy.CREATE_MERGE_COMMIT_IF_NECESSARY;
import static io.onedev.server.model.support.pullrequest.MergeStrategy.REBASE_SOURCE_BRANCH_COMMITS;
import static io.onedev.server.model.support.pullrequest.MergeStrategy.SQUASH_SOURCE_BRANCH_COMMITS;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.persistence.EntityNotFoundException;

import org.apache.commons.lang3.StringUtils;
import org.apache.wicket.Component;
import org.apache.wicket.Page;
import org.apache.wicket.RestartResponseException;
import org.apache.wicket.Session;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.form.OnChangeAjaxBehavior;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.ajax.markup.html.form.AjaxButton;
import org.apache.wicket.behavior.AttributeAppender;
import org.apache.wicket.core.request.handler.IPartialPageRequestHandler;
import org.apache.wicket.markup.ComponentTag;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.head.JavaScriptHeaderItem;
import org.apache.wicket.markup.head.OnDomReadyHeaderItem;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.DropDownChoice;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.markup.html.link.BookmarkablePageLink;
import org.apache.wicket.markup.html.link.Link;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.model.AbstractReadOnlyModel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.model.PropertyModel;
import org.apache.wicket.request.IRequestHandler;
import org.apache.wicket.request.Url;
import org.apache.wicket.request.cycle.IRequestCycleListener;
import org.apache.wicket.request.cycle.RequestCycle;
import org.apache.wicket.request.flow.RedirectToUrlException;
import org.apache.wicket.request.mapper.parameter.PageParameters;

import com.google.common.collect.Sets;

import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.PullRequestChangeManager;
import io.onedev.server.entitymanager.PullRequestManager;
import io.onedev.server.entitymanager.PullRequestReviewManager;
import io.onedev.server.entitymanager.PullRequestWatchManager;
import io.onedev.server.git.GitUtils;
import io.onedev.server.infomanager.UserInfoManager;
import io.onedev.server.model.AbstractEntity;
import io.onedev.server.model.Project;
import io.onedev.server.model.PullRequest;
import io.onedev.server.model.PullRequestReview;
import io.onedev.server.model.PullRequestVerification;
import io.onedev.server.model.PullRequestWatch;
import io.onedev.server.model.User;
import io.onedev.server.model.support.EntityWatch;
import io.onedev.server.model.support.pullrequest.MergePreview;
import io.onedev.server.model.support.pullrequest.MergeStrategy;
import io.onedev.server.model.support.pullrequest.ReviewResult;
import io.onedev.server.search.entity.EntityQuery;
import io.onedev.server.search.entity.pullrequest.PullRequestQuery;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.util.DateUtils;
import io.onedev.server.util.ProjectScopedNumber;
import io.onedev.server.web.WebSession;
import io.onedev.server.web.behavior.ReferenceInputBehavior;
import io.onedev.server.web.behavior.WebSocketObserver;
import io.onedev.server.web.component.branch.BranchLink;
import io.onedev.server.web.component.entity.nav.EntityNavPanel;
import io.onedev.server.web.component.entity.watches.EntityWatchesPanel;
import io.onedev.server.web.component.floating.FloatingPanel;
import io.onedev.server.web.component.link.DropdownLink;
import io.onedev.server.web.component.link.ViewStateAwarePageLink;
import io.onedev.server.web.component.modal.ModalLink;
import io.onedev.server.web.component.modal.ModalPanel;
import io.onedev.server.web.component.project.gitprotocol.GitProtocolPanel;
import io.onedev.server.web.component.pullrequest.assignment.AssignmentListPanel;
import io.onedev.server.web.component.pullrequest.build.PullRequestJobsPanel;
import io.onedev.server.web.component.pullrequest.review.ReviewListPanel;
import io.onedev.server.web.component.sideinfo.SideInfoLink;
import io.onedev.server.web.component.sideinfo.SideInfoPanel;
import io.onedev.server.web.component.tabbable.PageTab;
import io.onedev.server.web.component.tabbable.PageTabHead;
import io.onedev.server.web.component.tabbable.Tab;
import io.onedev.server.web.component.tabbable.Tabbable;
import io.onedev.server.web.component.user.ident.Mode;
import io.onedev.server.web.component.user.ident.UserIdentPanel;
import io.onedev.server.web.page.project.ProjectPage;
import io.onedev.server.web.page.project.pullrequests.InvalidPullRequestPage;
import io.onedev.server.web.page.project.pullrequests.ProjectPullRequestsPage;
import io.onedev.server.web.page.project.pullrequests.create.NewPullRequestPage;
import io.onedev.server.web.page.project.pullrequests.detail.activities.PullRequestActivitiesPage;
import io.onedev.server.web.page.project.pullrequests.detail.changes.PullRequestChangesPage;
import io.onedev.server.web.page.project.pullrequests.detail.codecomments.PullRequestCodeCommentsPage;
import io.onedev.server.web.page.project.pullrequests.detail.mergepreview.MergePreviewPage;
import io.onedev.server.web.page.project.pullrequests.detail.operationconfirm.CommentableOperationConfirmPanel;
import io.onedev.server.web.page.project.pullrequests.detail.operationconfirm.MergeConfirmPanel;
import io.onedev.server.web.util.ConfirmClickModifier;
import io.onedev.server.web.util.Cursor;
import io.onedev.server.web.util.CursorSupport;
import io.onedev.server.web.util.PullRequestAware;
import io.onedev.server.web.util.ReferenceTransformer;

@SuppressWarnings("serial")
public abstract class PullRequestDetailPage extends ProjectPage implements PullRequestAware {

	public static final String PARAM_REQUEST = "request";
	
	protected final IModel<PullRequest> requestModel;
	
	private boolean isEditingTitle;
	
	private String title;
	
	private Long latestUpdateId;
	
	private MergeStrategy mergeStrategy;
	
	public PullRequestDetailPage(PageParameters params) {
		super(params);
		
		String requestNumberString = params.get(PARAM_REQUEST).toString();
		if (StringUtils.isBlank(requestNumberString)) {
			throw new RestartResponseException(ProjectPullRequestsPage.class, 
					ProjectPullRequestsPage.paramsOf(getProject(), null, 0));
		}
		
		requestModel = new LoadableDetachableModel<PullRequest>() {

			@Override
			protected PullRequest load() {
				Long requestNumber = Long.valueOf(requestNumberString);
				PullRequest request = getPullRequestManager().find(getProject(), requestNumber);
				if (request == null) {
					throw new EntityNotFoundException("Unable to find pull request #" 
							+ requestNumber + " in project " + getProject());
				}
				else if (!request.getTargetProject().equals(getProject()))
					throw new RestartResponseException(getPageClass(), paramsOf(request));
				else
					return request;
			}

		};
		
		if (!getPullRequest().isValid()) {
			throw new RestartResponseException(InvalidPullRequestPage.class, 
					InvalidPullRequestPage.paramsOf(getPullRequest()));
		}
			
		latestUpdateId = requestModel.getObject().getLatestUpdate().getId();
	}

	private PullRequestManager getPullRequestManager() {
		return OneDev.getInstance(PullRequestManager.class);
	}
	
	private WebMarkupContainer newRequestHead() {
		WebMarkupContainer requestHead = new WebMarkupContainer("requestHead");
		requestHead.setOutputMarkupId(true);
		add(requestHead);
		
		requestHead.add(new Label("title", new LoadableDetachableModel<String>() {

			@Override
			protected String load() {
				PullRequest request = getPullRequest();
				ReferenceTransformer transformer = new ReferenceTransformer(request.getTargetProject(), null);
				String transformed = transformer.apply(request.getTitle());
				return "#" + request.getNumber() + " - " + transformed;
			}
			
		}) {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(!isEditingTitle);
			}
			
		}.setEscapeModelStrings(false));
		
		requestHead.add(new AjaxLink<Void>("edit") {

			@Override
			public void onClick(AjaxRequestTarget target) {
				isEditingTitle = true;
				
				target.add(requestHead);
			}

			@Override
			protected void onConfigure() {
				super.onConfigure();

				setVisible(!isEditingTitle && SecurityUtils.canModify(getPullRequest()));
			}
			
		});
		
		requestHead.add(new BookmarkablePageLink<Void>("create", 
				NewPullRequestPage.class, NewPullRequestPage.paramsOf(getProject())) {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(!isEditingTitle);
			}
			
		});

		Form<?> form = new Form<Void>("editForm") {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(isEditingTitle);
			}
			
		};
		requestHead.add(form);
		
		title = getPullRequest().getTitle();
		form.add(new TextField<String>("title", new IModel<String>() {

			@Override
			public void detach() {
			}

			@Override
			public String getObject() {
				return title;
			}

			@Override
			public void setObject(String object) {
				title = object;
			}
			
		}).add(new ReferenceInputBehavior(false) {

			@Override
			protected Project getProject() {
				return PullRequestDetailPage.this.getProject();
			}
			
		}));
		
		form.add(new AjaxButton("save") {

			@Override
			protected void onSubmit(AjaxRequestTarget target, Form<?> form) {
				super.onSubmit(target, form);
				
				if (StringUtils.isNotBlank(title)) {
					OneDev.getInstance(PullRequestChangeManager.class).changeTitle(getPullRequest(), title);
					isEditingTitle = false;
				}

				target.add(requestHead);
				target.appendJavaScript("setTimeout(function() {$(window).resize();}, 0);");
			}
			
		});
		
		form.add(new AjaxLink<Void>("cancel") {

			@Override
			public void onClick(AjaxRequestTarget target) {
				isEditingTitle = false;
				target.add(requestHead);
				target.appendJavaScript("setTimeout(function() {$(window).resize();}, 0);");
			}
			
		});
		
		return requestHead;
	}
	
	@Override
	protected void onInitialize() {
		super.onInitialize();

		add(newRequestHead());
		add(newStatusAndBranchesContainer());
		WebMarkupContainer summaryContainer = new WebMarkupContainer("requestSummary") {

			@Override
			public void renderHead(IHeaderResponse response) {
				super.renderHead(response);
				response.render(OnDomReadyHeaderItem.forScript("onedev.server.pullRequestDetail.onSummaryDomReady();"));
			}
			
		};
		
		summaryContainer.add(new WebSocketObserver() {

			@Override
			public Collection<String> getObservables() {
				return Sets.newHashSet(PullRequest.getWebSocketObservable(getPullRequest().getId()));
			}

			@Override
			public void onObservableChanged(IPartialPageRequestHandler handler) {
				handler.add(component);
				handler.appendJavaScript("setTimeout(function() {$(window).resize();}, 0);");
			}
			
		});
		summaryContainer.setOutputMarkupId(true);
		add(summaryContainer);

		summaryContainer.add(new Label("checkError", new AbstractReadOnlyModel<String>() {

			@Override
			public String getObject() {
				return getPullRequest().getCheckError();
			}
			
		}) {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(getPullRequest().getCheckError() != null);
			}
			
		});

		summaryContainer.add(new WebMarkupContainer("discarded") {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(getPullRequest().isDiscarded());
			}
			
		});
		
		summaryContainer.add(new WebMarkupContainer("fastForwarded") {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				
				PullRequest request = getPullRequest();
				MergePreview preview = request.getLastMergePreview();
				setVisible(request.isMerged() && preview != null 
						&& preview.getHeadCommitHash().equals(preview.getMergeCommitHash()));
			}
			
		});
		summaryContainer.add(new WebMarkupContainer("merged") {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				
				PullRequest request = getPullRequest();
				MergePreview preview = request.getLastMergePreview();
				setVisible(request.isMerged() && preview != null 
						&& (preview.getMergeStrategy() == CREATE_MERGE_COMMIT 
								|| preview.getMergeStrategy() == CREATE_MERGE_COMMIT_IF_NECESSARY 
										&& !preview.getHeadCommitHash().equals(preview.getMergeCommitHash())));
			}
			
		});
		summaryContainer.add(new WebMarkupContainer("mergedOutside") {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				
				setVisible(getPullRequest().isMerged() && getPullRequest().getLastMergePreview() == null);
			}
			
		});
		summaryContainer.add(new WebMarkupContainer("squashed") {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				
				PullRequest request = getPullRequest();
				MergePreview preview = request.getLastMergePreview();
				setVisible(request.isMerged() && preview != null 
						&& preview.getMergeStrategy() == SQUASH_SOURCE_BRANCH_COMMITS);
			}
			
		});
		summaryContainer.add(new WebMarkupContainer("rebased") {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				
				PullRequest request = getPullRequest();
				MergePreview preview = request.getMergePreview();
				setVisible(request.isMerged() && preview != null 
						&& preview.getMergeStrategy() == REBASE_SOURCE_BRANCH_COMMITS);
			}
			
		});
		
		summaryContainer.add(new WebMarkupContainer("calculatingMergePreview") {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(getPullRequest().isOpen() && getPullRequest().getMergePreview() == null);
			}
			
		});
		summaryContainer.add(new WebMarkupContainer("hasMergeConflict") {
			
			@Override
			protected void onInitialize() {
				super.onInitialize();
				
				add(new DropdownLink("resolveInstructions") {

					@Override
					protected void onInitialize(FloatingPanel dropdown) {
						dropdown.add(AttributeAppender.append("class", "conflict-resolve-instruction"));
					}
					
					@Override
					protected void onConfigure() {
						super.onConfigure();
						setVisible(getPullRequest().getSource() != null);
					}

					@Override
					protected Component newContent(String id, FloatingPanel dropdown) {
						if (getPullRequest().getTargetProject().equals(getPullRequest().getSourceProject())) {
							return new ConflictResolveInstructionPanel(id) {

								@Override
								protected PullRequest getPullRequest() {
									return PullRequestDetailPage.this.getPullRequest();
								}

							};
						} else {
							return new GitProtocolPanel(id) {
								
								@Override
								protected Component newContent(String componentId) {
									return new ConflictResolveInstructionPanel(componentId) {

										@Override
										protected PullRequest getPullRequest() {
											return PullRequestDetailPage.this.getPullRequest();
										}

									};
								}
								
								@Override
								protected Project getProject() {
									return getPullRequest().getTargetProject();
								}

							};
						}
					}
					
				});
			}

			@Override
			protected void onConfigure() {
				super.onConfigure();
				MergePreview preview = getPullRequest().getMergePreview();
				setVisible(getPullRequest().isOpen() 
						&& preview != null 
						&& preview.getMergeCommitHash() == null);
			}

		});
		summaryContainer.add(new WebMarkupContainer("calculatedMergePreview") {
			
			@Override
			protected void onConfigure() {
				super.onConfigure();
				MergePreview preview = getPullRequest().getMergePreview();
				setVisible(getPullRequest().isOpen() 
						&& preview != null 
						&& preview.getMergeCommitHash() != null);
			}

		});		
		
		summaryContainer.add(new WebMarkupContainer("requestedForChanges") {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(getPullRequest().isOpen() && requestedForChanges());
			}
			
		});
		summaryContainer.add(new WebMarkupContainer("waitingForReviews") {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				
				setVisible(getPullRequest().isOpen() && !requestedForChanges() 
						&& getPullRequest().getReviews().stream().anyMatch(it-> it.getResult()==null));
			}
			
		});
		summaryContainer.add(new WebMarkupContainer("unsuccessfulBuilds") {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(getPullRequest().isOpen() && hasUnsuccessfulRequiredBuilds());
			}
			
		});
		summaryContainer.add(new WebMarkupContainer("unfinishedBuilds") {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				
				Stream<PullRequestVerification> stream = getPullRequest().getVerifications().stream();
				setVisible(getPullRequest().isOpen() && !hasUnsuccessfulRequiredBuilds() 
						&& stream.anyMatch(it-> it.isRequired() && !it.getBuild().isFinished()));
			}
			
		});
		summaryContainer.add(new WebMarkupContainer("mergeableByCodeWriters") {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(getPullRequest().isOpen() && !SecurityUtils.canWriteCode(getProject()) 
						&& getPullRequest().isAllReviewsApproved() && getPullRequest().isRequiredBuildsSuccessful());
			}
			
		});
		
		add(newOperationsContainer());
		add(newMoreInfoContainer());
		
		List<Tab> tabs = new ArrayList<>();
		
		tabs.add(new RequestTab("Activities", PullRequestActivitiesPage.class) {

			@Override
			protected Component renderOptions(String componentId) {
				PullRequestActivitiesPage page = (PullRequestActivitiesPage) getPage();
				return page.renderOptions(componentId);
			}
			
		});
		tabs.add(new RequestTab("File Changes", PullRequestChangesPage.class));
		tabs.add(new RequestTab("Code Comments", PullRequestCodeCommentsPage.class));
		if (getPullRequest().isOpen())
			tabs.add(new RequestTab("Merge Preview", MergePreviewPage.class));
		
		add(new Tabbable("requestTabs", tabs).setOutputMarkupId(true));
		
		RequestCycle.get().getListeners().add(new IRequestCycleListener() {
			
			@Override
			public void onUrlMapped(RequestCycle cycle, IRequestHandler handler, Url url) {
			}
			
			@Override
			public void onRequestHandlerScheduled(RequestCycle cycle, IRequestHandler handler) {
			}
			
			@Override
			public void onRequestHandlerResolved(RequestCycle cycle, IRequestHandler handler) {
			}
			
			@Override
			public void onRequestHandlerExecuted(RequestCycle cycle, IRequestHandler handler) {
			}
			
			@Override
			public void onExceptionRequestHandlerResolved(RequestCycle cycle, IRequestHandler handler, Exception exception) {
			}
			
			@Override
			public IRequestHandler onException(RequestCycle cycle, Exception ex) {
				return null;
			}
			
			@Override
			public void onEndRequest(RequestCycle cycle) {
				if (SecurityUtils.getUser() != null) 
					OneDev.getInstance(UserInfoManager.class).visitPullRequest(SecurityUtils.getUser(), getPullRequest());
			}
			
			@Override
			public void onDetach(RequestCycle cycle) {
			}
			
			@Override
			public void onBeginRequest(RequestCycle cycle) {
			}
			
		});
	}
	
	private boolean requestedForChanges() {
		return getPullRequest().getReviews().stream().anyMatch(
				it-> it.getResult()!=null && !it.getResult().isApproved());
	}
	
	private boolean hasUnsuccessfulRequiredBuilds() {
		return getPullRequest().getVerifications().stream().anyMatch(
				it-> it.isRequired() && it.getBuild().isFinished() && !it.getBuild().isSuccessful());		
	}
	
	private WebMarkupContainer newMoreInfoContainer() {
		return new SideInfoPanel("moreInfo") {

			@Override
			protected Component newContent(String componentId) {
				Fragment fragment = new Fragment(componentId, "moreInfoFrag", PullRequestDetailPage.this);
				fragment.add(new EntityNavPanel<PullRequest>("requestNav") {

					@Override
					protected EntityQuery<PullRequest> parse(String queryString, boolean inProject) {
						return PullRequestQuery.parse(inProject?getProject():null, queryString);
					}

					@Override
					protected PullRequest getEntity() {
						return getPullRequest();
					}

					@Override
					protected List<PullRequest> query(EntityQuery<PullRequest> query, int offset, int count, boolean inProject) {
						return getPullRequestManager().query(inProject?getProject():null, query, offset, count, false, false);
					}

					@Override
					protected CursorSupport<PullRequest> getCursorSupport() {
						return new CursorSupport<PullRequest>() {

							@Override
							public Cursor getCursor() {
								return WebSession.get().getPullRequestCursor();
							}

							@Override
							public void navTo(AjaxRequestTarget target, PullRequest entity, Cursor cursor) {
								WebSession.get().setPullRequestCursor(cursor);
								setResponsePage(getPageClass(), paramsOf(entity));
							}
							
						};
					}
					
				});
				
				fragment.add(newMergeStrategyContainer());
				fragment.add(new ReviewListPanel("reviews") {

					@Override
					protected PullRequest getPullRequest() {
						return PullRequestDetailPage.this.getPullRequest();
					}
					
				});
				fragment.add(new WebMarkupContainer("reviewerHelp") {

					@Override
					protected void onConfigure() {
						super.onConfigure();
						setVisible(!getPullRequest().isMerged() && SecurityUtils.canModify(getPullRequest()));
					}
					
				});
				fragment.add(new WebMarkupContainer("hiddenJobsNote") {

					@Override
					protected void onConfigure() {
						super.onConfigure();
						
						boolean hasHiddenJobs = false;
						for (String jobName: getPullRequest().getVerifications().stream()
								.map(it->it.getBuild().getJobName()).collect(Collectors.toSet())) {
							if (!SecurityUtils.canAccess(getProject(), jobName)) {
								hasHiddenJobs = true;
								break;
							}
						}
						setVisible(hasHiddenJobs);
					}
					
				});
				fragment.add(new PullRequestJobsPanel("jobs") {

					@Override
					protected PullRequest getPullRequest() {
						return PullRequestDetailPage.this.getPullRequest();
					}
					
					@Override
					protected void onConfigure() {
						super.onConfigure();
						setVisible(!getPullRequest().getVerifications().isEmpty());
					}

				});
				fragment.add(new WebMarkupContainer("jobsHelp") {
					
					@Override
					protected void onConfigure() {
						super.onConfigure();

						boolean hasVisibleRequiredJobs = false;
						for (String jobName: getPullRequest().getVerifications().stream()
								.filter(it->it.isRequired())
								.map(it->it.getBuild().getJobName())
								.collect(Collectors.toSet())) {
							if (SecurityUtils.canAccess(getProject(), jobName)) {
								hasVisibleRequiredJobs = true;
								break;
							}
						}
						setVisible(hasVisibleRequiredJobs);
					}
					
				});
				fragment.add(new AssignmentListPanel("assignments") {

					@Override
					protected PullRequest getPullRequest() {
						return PullRequestDetailPage.this.getPullRequest();
					}
					
				});
				fragment.add(new WebMarkupContainer("assigneeHelp") {

					@Override
					protected void onConfigure() {
						super.onConfigure();
						setVisible(!getPullRequest().isMerged() && SecurityUtils.canModify(getPullRequest()));
					}
					
				});
				
				fragment.add(new EntityWatchesPanel("watches") {

					@Override
					protected void onSaveWatch(EntityWatch watch) {
						OneDev.getInstance(PullRequestWatchManager.class).save((PullRequestWatch) watch);
					}

					@Override
					protected void onDeleteWatch(EntityWatch watch) {
						OneDev.getInstance(PullRequestWatchManager.class).delete((PullRequestWatch) watch);
					}

					@Override
					protected AbstractEntity getEntity() {
						return getPullRequest();
					}
					
				});
				
				fragment.add(newManageContainer());
				
				fragment.add(new WebSocketObserver() {

					@Override
					public Collection<String> getObservables() {
						return Sets.newHashSet(PullRequest.getWebSocketObservable(getPullRequest().getId()));
					}

					@Override
					public void onObservableChanged(IPartialPageRequestHandler handler) {
						handler.add(component);
					}
					
				});

				fragment.setOutputMarkupId(true);
				return fragment;
			}

		};
	}
	
	private WebMarkupContainer newManageContainer() {
		WebMarkupContainer container = new WebMarkupContainer("manage");
		container.setVisible(SecurityUtils.canManage(getPullRequest().getTargetProject()));
		container.add(new Link<Void>("synchronize") {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(getPullRequest().isOpen());
			}

			@Override
			public void onClick() {
				getPullRequestManager().check(getPullRequest());
				if (getPullRequest().getCheckError() == null) 
					Session.get().success("Pull request is synchronized");
			}
			
		});
		container.add(new Link<Void>("delete") {

			@Override
			public void onClick() {
				PullRequest request = getPullRequest();
				getPullRequestManager().delete(request);
				Session.get().success("Pull request #" + request.getNumber() + " deleted");
				
				String redirectUrlAfterDelete = WebSession.get().getRedirectUrlAfterDelete(PullRequest.class);
				if (redirectUrlAfterDelete != null)
					throw new RedirectToUrlException(redirectUrlAfterDelete);
				else
					setResponsePage(ProjectPullRequestsPage.class, ProjectPullRequestsPage.paramsOf(getProject()));
			}
			
		}.add(new ConfirmClickModifier("Do you really want to delete this pull request?")));
		return container;
	}
	
	private WebMarkupContainer newMergeStrategyContainer() {
		WebMarkupContainer mergeStrategyContainer = new WebMarkupContainer("mergeStrategy");
		mergeStrategyContainer.setOutputMarkupId(true);

		mergeStrategy = getPullRequest().getMergeStrategy();
		
		IModel<MergeStrategy> mergeStrategyModel = new PropertyModel<MergeStrategy>(this, "mergeStrategy");
		
		List<MergeStrategy> mergeStrategies = Arrays.asList(MergeStrategy.values());
		DropDownChoice<MergeStrategy> editor = 
				new DropDownChoice<MergeStrategy>("editor", mergeStrategyModel, mergeStrategies) {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				
				setVisible(!getPullRequest().isMerged() && SecurityUtils.canModify(getPullRequest()));						
			}
			
		};
		editor.add(new OnChangeAjaxBehavior() {
					
			@Override
			protected void onUpdate(AjaxRequestTarget target) {
				OneDev.getInstance(PullRequestChangeManager.class).changeMergeStrategy(getPullRequest(), mergeStrategy);
			}
			
		});
		mergeStrategyContainer.add(editor);
		
		mergeStrategyContainer.add(new Label("viewer", new AbstractReadOnlyModel<String>() {

			@Override
			public String getObject() {
				return getPullRequest().getMergeStrategy().toString();
			}
			
		}) {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				
				setVisible(getPullRequest().isMerged() || !SecurityUtils.canModify(getPullRequest()));						
			}
			
		});

		mergeStrategyContainer.add(new Label("help", new AbstractReadOnlyModel<String>() {

			@Override
			public String getObject() {
				return getPullRequest().getMergeStrategy().getDescription();
			}
			
		}) {
			
			@Override
			protected void onConfigure() {
				super.onConfigure();
				
				setVisible(!getPullRequest().isMerged() && SecurityUtils.canModify(getPullRequest()));						
			}
			
		});
		
		return mergeStrategyContainer;
	}
	
	private WebMarkupContainer newStatusAndBranchesContainer() {
		WebMarkupContainer statusAndBranchesContainer = new WebMarkupContainer("statusAndBranches");
		
		PullRequest request = getPullRequest();
		
		statusAndBranchesContainer.add(new Label("status", new AbstractReadOnlyModel<String>() {

			@Override
			public String getObject() {
				PullRequest request = getPullRequest();
				if (request.isOpen())
					return "OPEN";
				else
					return request.getCloseInfo().getStatus().toString();
			}
			
		}) {

			@Override
			protected void onInitialize() {
				super.onInitialize();

				add(new WebSocketObserver() {
					
					@Override
					public void onObservableChanged(IPartialPageRequestHandler handler) {
						handler.add(component);
					}
					
					@Override
					public Collection<String> getObservables() {
						return Sets.newHashSet(PullRequest.getWebSocketObservable(getPullRequest().getId()));
					}
					
				});
				
				add(AttributeAppender.append("class", new AbstractReadOnlyModel<String>() {

					@Override
					public String getObject() {
						PullRequest request = getPullRequest();
						if (request.isDiscarded())
							return " badge-danger";
						else if (request.isMerged())
							return " badge-success";
						else
							return " badge-warning";
					}
					
				}));
				setOutputMarkupId(true);
			}
			
		});
		
		User submitter = User.from(request.getSubmitter(), request.getSubmitterName());
		statusAndBranchesContainer.add(new UserIdentPanel("user", submitter, Mode.NAME));
		statusAndBranchesContainer.add(new Label("date", DateUtils.formatAge(request.getSubmitDate())));
		
		statusAndBranchesContainer.add(new BranchLink("target", request.getTarget()));
		if (request.getSourceProject() != null) {
			statusAndBranchesContainer.add(new BranchLink("source", request.getSource()));
		} else {
			statusAndBranchesContainer.add(new Label("source", "unknown") {

				@Override
				protected void onComponentTag(ComponentTag tag) {
					super.onComponentTag(tag);
					tag.setName("span");
				}
				
			});
		}
		
		statusAndBranchesContainer.add(new SideInfoLink("moreInfo"));
		
		return statusAndBranchesContainer;
	}

	private WebMarkupContainer newOperationsContainer() {
		WebMarkupContainer operationsContainer = new WebMarkupContainer("requestOperations");
		operationsContainer.add(new WebSocketObserver() {

			@Override
			public Collection<String> getObservables() {
				return Sets.newHashSet(PullRequest.getWebSocketObservable(getPullRequest().getId()));
			}

			@Override
			public void onObservableChanged(IPartialPageRequestHandler handler) {
				handler.add(component);
				handler.appendJavaScript("setTimeout(function() {$(window).resize();}, 0);");
			}
			
		});
		
		operationsContainer.setOutputMarkupPlaceholderTag(true);
		
		operationsContainer.setVisible(SecurityUtils.getUser() != null);
		
		operationsContainer.add(new ModalLink("approve") {

			@Override
			protected String getModalCssClass() {
				return "modal-lg";
			}

			private boolean canOperate() {
				PullRequest request = getPullRequest();
				if (request.isOpen()) {
					PullRequestReview review = request.getReview(SecurityUtils.getUser());
					return review == null || review.getResult() == null || !review.getResult().isApproved();
				} else {
					return false;
				}
			}
			
			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(canOperate());
			}

			@Override
			protected Component newContent(String id, ModalPanel modal) {
				return new CommentableOperationConfirmPanel(id, modal, latestUpdateId) {
					
					@Override
					protected boolean operate() {
						if (canOperate()) {
							User user = SecurityUtils.getUser();
							PullRequest request = getPullRequest();
							PullRequestReview review = request.getReview(user);
							if (review == null) {
								review = new PullRequestReview();
								review.setRequest(request);
								review.setUser(user);
							}
							ReviewResult result = new ReviewResult();
							result.setApproved(true);
							result.setComment(getComment());
							result.setCommit(request.getLatestUpdate().getHeadCommitHash());
							review.setResult(result);
							OneDev.getInstance(PullRequestReviewManager.class).review(review);
							Session.get().success("Approved");
							return true;
						} else {
							return false; 
						}
					}
					
					@Override
					protected String getTitle() {
						return "Confirm Approve";
					}
				};
			}
			
		});
		
		operationsContainer.add(new ModalLink("requestForChanges") {

			@Override
			protected String getModalCssClass() {
				return "modal-lg";
			}

			private boolean canOperate() {
				PullRequest request = getPullRequest();
				if (request.isOpen()) {
					PullRequestReview review = request.getReview(SecurityUtils.getUser());
					return review == null || review.getResult() == null || review.getResult().isApproved();
				} else {
					return false;
				}
			}
			
			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(canOperate());
			}

			@Override
			protected Component newContent(String id, ModalPanel modal) {
				return new CommentableOperationConfirmPanel(id, modal, latestUpdateId) {
					
					@Override
					protected boolean operate() {
						if (canOperate()) {
							User user = SecurityUtils.getUser();
							PullRequest request = getPullRequest();
							PullRequestReview review = request.getReview(user);
							if (review == null) {
								review = new PullRequestReview();
								review.setRequest(request);
								review.setUser(user);
							}
							ReviewResult result = new ReviewResult();
							result.setApproved(false);
							result.setComment(getComment());
							result.setCommit(request.getLatestUpdate().getHeadCommitHash());
							review.setResult(result);
							OneDev.getInstance(PullRequestReviewManager.class).review(review);
							Session.get().success("Requested For changes");
							return true;
						} else {
							return false; 
						}
					}
					
					@Override
					protected String getTitle() {
						return "Confirm Request For Changes";
					}
				};
			}
			
		});
		
		operationsContainer.add(new ModalLink("merge") {

			private boolean canOperate() {
				PullRequest request = getPullRequest();
				MergePreview preview = request.getMergePreview();
				return request.isOpen()
						&& request.isAllReviewsApproved() 
						&& request.isRequiredBuildsSuccessful()
						&& request.getCheckError() == null 
						&& preview != null 
						&& preview.getMergeCommitHash() != null
						&& SecurityUtils.canWriteCode(request.getTargetProject());
			}
			
			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(canOperate());
			}

			@Override
			protected Component newContent(String id, ModalPanel modal) {
				return new MergeConfirmPanel(id, modal, latestUpdateId) {

					@Override
					protected boolean operate() {
						if (canOperate()) {
							OneDev.getInstance(PullRequestManager.class).merge(getPullRequest(), getCommitMessage());
							return true;
						} else {
							return false;
						}
					}
					
				};
			}
			
		});
		
		operationsContainer.add(new ModalLink("discard") {

			@Override
			protected String getModalCssClass() {
				return "modal-lg";
			}

			private boolean canOperate() {
				return getPullRequest().isOpen() && SecurityUtils.canModify(getPullRequest());
			}
			
			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(canOperate());
			}

			@Override
			protected Component newContent(String id, ModalPanel modal) {
				return new CommentableOperationConfirmPanel(id, modal, latestUpdateId) {
					
					@Override
					protected boolean operate() {
						if (canOperate()) {
							OneDev.getInstance(PullRequestManager.class).discard(getPullRequest(), getComment());			
							return true;
						} else {
							return false; 
						}
					}
					
					@Override
					protected String getTitle() {
						return "Confirm Discard";
					}
				};
			}
			
		});
		
		operationsContainer.add(new ModalLink("reopen") {

			@Override
			protected String getModalCssClass() {
				return "modal-lg";
			}

			private boolean canOperate() {
				PullRequest request = getPullRequest();
				PullRequestManager pullRequestManager = OneDev.getInstance(PullRequestManager.class);
				return !request.isOpen() 
						&& SecurityUtils.canModify(request)
						&& request.getTarget().getObjectName(false) != null
						&& request.getSourceProject() != null 
						&& request.getSource().getObjectName(false) != null
						&& pullRequestManager.findEffective(request.getTarget(), request.getSource()) == null
						&& !GitUtils.isMergedInto(request.getTargetProject().getRepository(), null,
								request.getSource().getObjectId(), request.getTarget().getObjectId());
			}
			
			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(canOperate());
			}

			@Override
			protected Component newContent(String id, ModalPanel modal) {
				return new CommentableOperationConfirmPanel(id, modal, latestUpdateId) {
					
					@Override
					protected boolean operate() {
						if (canOperate()) {
							OneDev.getInstance(PullRequestManager.class).reopen(getPullRequest(), getComment());
							return true;
						} else {
							return false; 
						}
					}
					
					@Override
					protected String getTitle() {
						return "Confirm Reopen";
					}
				};
			}
			
		});
		
		operationsContainer.add(new ModalLink("deleteSourceBranch") {

			@Override
			protected String getModalCssClass() {
				return "modal-lg";
			}

			private boolean canOperate() {
				PullRequest request = getPullRequest();
				PullRequestManager pullRequestManager = OneDev.getInstance(PullRequestManager.class);
				MergePreview preview = request.getLastMergePreview();
				return request.isMerged()
						&& request.getSourceProject() != null		
						&& request.getSource().getObjectName(false) != null
						&& !request.getSource().isDefault()
						&& preview != null
						&& (request.getSource().getObjectName().equals(preview.getHeadCommitHash()) 
								|| request.getSource().getObjectName().equals(preview.getMergeCommitHash()))
						&& SecurityUtils.canModify(request)
						&& SecurityUtils.canDeleteBranch(request.getSourceProject(), request.getSourceBranch())
						&& pullRequestManager.queryOpenTo(request.getSource()).isEmpty();
			}
			
			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(canOperate());
			}

			@Override
			protected Component newContent(String id, ModalPanel modal) {
				return new CommentableOperationConfirmPanel(id, modal, latestUpdateId) {
					
					@Override
					protected boolean operate() {
						if (canOperate()) {
							OneDev.getInstance(PullRequestManager.class).deleteSourceBranch(getPullRequest(), getComment());
							Session.get().success("Deleted source branch");
							return true;
						} else {
							return false; 
						}
					}
					
					@Override
					protected String getTitle() {
						return "Confirm Delete Source Branch";
					}
				};
			}
			
		});
		
		operationsContainer.add(new ModalLink("restoreSourceBranch") {

			@Override
			protected String getModalCssClass() {
				return "modal-lg";
			}

			private boolean canOperate() {
				PullRequest request = getPullRequest();
				return request.getSourceProject() != null 
						&& request.getSource().getObjectName(false) == null 
						&& SecurityUtils.canModify(request) 
						&& SecurityUtils.canWriteCode(request.getSourceProject());
			}
			
			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(canOperate());
			}

			@Override
			protected Component newContent(String id, ModalPanel modal) {
				return new CommentableOperationConfirmPanel(id, modal, latestUpdateId) {
					
					@Override
					protected boolean operate() {
						if (canOperate()) {
							OneDev.getInstance(PullRequestManager.class).restoreSourceBranch(getPullRequest(), getComment());
							Session.get().success("Restored source branch");
							return true;
						} else {
							return false; 
						}
					}
					
					@Override
					protected String getTitle() {
						return "Confirm Restore Source Branch";
					}
				};
			}
			
		});
		
		return operationsContainer;
	}
	
	@Override
	protected void onDetach() {
		requestModel.detach();
		super.onDetach();
	}

	public static PageParameters paramsOf(PullRequest request) {
		return paramsOf(request.getFQN());
	}
	
	public static PageParameters paramsOf(ProjectScopedNumber requestFQN) {
		PageParameters params = ProjectPage.paramsOf(requestFQN.getProject());
		params.add(PARAM_REQUEST, requestFQN.getNumber());
		return params;
	}
	
	@Override
	public PullRequest getPullRequest() {
		return requestModel.getObject();
	}
	
	@Override
	public void renderHead(IHeaderResponse response) {
		super.renderHead(response);
		
		response.render(JavaScriptHeaderItem.forReference(new PullRequestDetailResourceReference()));
		response.render(OnDomReadyHeaderItem.forScript("onedev.server.pullRequestDetail.onDomReady();"));
	}
	
	@Override
	protected boolean isPermitted() {
		return SecurityUtils.canReadCode(getProject());
	}
	
	private class RequestTab extends PageTab {

		public RequestTab(String title, Class<? extends Page> pageClass) {
			super(Model.of(title), pageClass);
		}
		
		@Override
		public Component render(String componentId) {
			if (getMainPageClass() == PullRequestCodeCommentsPage.class) {
				Fragment fragment = new Fragment(componentId, "codeCommentsTabLinkFrag", PullRequestDetailPage.this);
				Link<Void> link = new ViewStateAwarePageLink<Void>("link", 
						PullRequestCodeCommentsPage.class, paramsOf(getPullRequest()));
				link.add(AttributeAppender.append("class", new LoadableDetachableModel<String>() {

					@Override
					protected String load() {
						PullRequest request = getPullRequest();
						if (request.getLastCodeCommentActivityDate() != null 
								&& !request.isCodeCommentsVisitedAfter(request.getLastCodeCommentActivityDate())) {
							return "new";
						} else {
							return "";
						}
					}
					
				}));
				link.add(new WebSocketObserver() {

					@Override
					public Collection<String> getObservables() {
						return Sets.newHashSet(PullRequest.getWebSocketObservable(getPullRequest().getId()));
					}

					@Override
					public void onObservableChanged(IPartialPageRequestHandler handler) {
						handler.add(component);
					}
					
				});
				link.setOutputMarkupId(true);
				fragment.add(link);
				return fragment;
			} else {
				return new PageTabHead(componentId, this) {

					@Override
					protected Link<?> newLink(String linkId, Class<? extends Page> pageClass) {
						return new ViewStateAwarePageLink<Void>(linkId, pageClass, paramsOf(getPullRequest()));
					}
					
				};
			}
		}
		
	}
	
}