package io.onedev.server.web.page.project.pullrequests.create;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.StringUtils;
import org.apache.wicket.Component;
import org.apache.wicket.RestartResponseAtInterceptPageException;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.attributes.AjaxRequestAttributes;
import org.apache.wicket.ajax.form.OnChangeAjaxBehavior;
import org.apache.wicket.behavior.AttributeAppender;
import org.apache.wicket.extensions.ajax.markup.html.AjaxLazyLoadPanel;
import org.apache.wicket.feedback.FencedFeedbackPanel;
import org.apache.wicket.markup.head.CssHeaderItem;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.Button;
import org.apache.wicket.markup.html.form.DropDownChoice;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.markup.html.link.Link;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.model.AbstractReadOnlyModel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.request.cycle.RequestCycle;
import org.apache.wicket.request.mapper.parameter.PageParameters;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.joda.time.DateTime;

import com.google.common.collect.Lists;

import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.CodeCommentManager;
import io.onedev.server.entitymanager.PullRequestManager;
import io.onedev.server.git.GitUtils;
import io.onedev.server.git.RefInfo;
import io.onedev.server.model.CodeComment;
import io.onedev.server.model.Project;
import io.onedev.server.model.PullRequest;
import io.onedev.server.model.PullRequestReview;
import io.onedev.server.model.PullRequestUpdate;
import io.onedev.server.model.User;
import io.onedev.server.model.support.MarkPos;
import io.onedev.server.model.support.pullrequest.CloseInfo;
import io.onedev.server.model.support.pullrequest.MergePreview;
import io.onedev.server.model.support.pullrequest.MergeStrategy;
import io.onedev.server.persistence.dao.Dao;
import io.onedev.server.search.commit.CommitQuery;
import io.onedev.server.search.commit.Revision;
import io.onedev.server.search.commit.RevisionCriteria;
import io.onedev.server.util.Pair;
import io.onedev.server.util.ProjectAndBranch;
import io.onedev.server.util.SecurityUtils;
import io.onedev.server.util.diff.WhitespaceOption;
import io.onedev.server.web.ajaxlistener.DisableGlobalLoadingIndicatorListener;
import io.onedev.server.web.behavior.ReferenceInputBehavior;
import io.onedev.server.web.component.branch.BranchLink;
import io.onedev.server.web.component.branch.picker.AffinalBranchPicker;
import io.onedev.server.web.component.commit.list.CommitListPanel;
import io.onedev.server.web.component.diff.revision.CommentSupport;
import io.onedev.server.web.component.diff.revision.RevisionDiffPanel;
import io.onedev.server.web.component.link.ViewStateAwarePageLink;
import io.onedev.server.web.component.markdown.AttachmentSupport;
import io.onedev.server.web.component.project.comment.CommentInput;
import io.onedev.server.web.component.review.ReviewListPanel;
import io.onedev.server.web.component.tabbable.AjaxActionTab;
import io.onedev.server.web.component.tabbable.Tab;
import io.onedev.server.web.component.tabbable.Tabbable;
import io.onedev.server.web.page.project.ProjectPage;
import io.onedev.server.web.page.project.commits.CommitDetailPage;
import io.onedev.server.web.page.project.compare.RevisionComparePage;
import io.onedev.server.web.page.project.pullrequests.detail.PullRequestDetailPage;
import io.onedev.server.web.page.project.pullrequests.detail.activities.PullRequestActivitiesPage;
import io.onedev.server.web.page.security.LoginPage;
import io.onedev.server.web.util.ProjectAttachmentSupport;

@SuppressWarnings("serial")
public class NewPullRequestPage extends ProjectPage implements CommentSupport {

	private static final String TABS_ID = "tabs";
	
	private static final String TAB_PANEL_ID = "tabPanel";
	
	private ProjectAndBranch target;
	
	private ProjectAndBranch source;
	
	private IModel<PullRequest> requestModel;
	
	private Long commentId;
	
	private MarkPos mark;
	
	private String pathFilter;
	
	private String blameFile;
	
	private WhitespaceOption whitespaceOption = WhitespaceOption.DEFAULT;
	
	public static PageParameters paramsOf(Project project, ProjectAndBranch target, ProjectAndBranch source) {
		PageParameters params = paramsOf(project);
		if (target.getBranch() != null)
			params.set("target", target.toString());
		else
			params.set("target", target.getProjectId());
		if (source.getBranch() != null)
			params.set("source", source.toString());
		else
			params.set("source", source.getProjectId());
		return params;
	}

	private String suggestSourceBranch() {
		User user = getLoginUser();
		List<Pair<String, Integer>> branchUpdates = new ArrayList<>(); 
		for (RefInfo refInfo: getProject().getBranchRefInfos()) {
			RevCommit commit = (RevCommit) refInfo.getPeeledObj();
			if (commit.getAuthorIdent().getEmailAddress().equals(user.getEmail()))
				branchUpdates.add(new Pair<>(GitUtils.ref2branch(refInfo.getRef().getName()), commit.getCommitTime()));
		}
		branchUpdates.sort(Comparator.comparing(Pair::getSecond));
		if (!branchUpdates.isEmpty())
			return branchUpdates.get(branchUpdates.size()-1).getFirst();
		else
			return getProject().getDefaultBranch();
	}
	
	public NewPullRequestPage(PageParameters params) {
		super(params);
		
		User currentUser = getLoginUser();
		if (currentUser == null)
			throw new RestartResponseAtInterceptPageException(LoginPage.class);

		String targetParam = params.get("target").toString();
		String sourceParam = params.get("source").toString();
		String suggestedSourceBranch = null;
		if (targetParam != null) {
			target = new ProjectAndBranch(targetParam);
		} else {
			suggestedSourceBranch = suggestSourceBranch();
			if (suggestedSourceBranch != null) {
				if (!suggestedSourceBranch.equals(getProject().getDefaultBranch())) {
					target = new ProjectAndBranch(getProject(), getProject().getDefaultBranch());
	 			} else if (getProject().getForkedFrom() != null && SecurityUtils.canReadCode(getProject().getForkedFrom())) {
					target = new ProjectAndBranch(getProject().getForkedFrom(), 
							getProject().getForkedFrom().getDefaultBranch());
				} else {
					target = new ProjectAndBranch(getProject(), getProject().getDefaultBranch());
				}
			} else {
				target = new ProjectAndBranch(getProject(), null);
			}
		}
		
		if (sourceParam != null) {
			source = new ProjectAndBranch(sourceParam);
		} else {
			if (suggestedSourceBranch == null) 
				suggestedSourceBranch = suggestSourceBranch();
			source = new ProjectAndBranch(getProject(), suggestedSourceBranch);
		}

		AtomicReference<PullRequest> pullRequestRef = new AtomicReference<>(null);
		PullRequest prevRequest = OneDev.getInstance(PullRequestManager.class).findLatest(getProject(), getLoginUser());
		if (prevRequest != null && source.equals(prevRequest.getSource()) && target.equals(prevRequest.getTarget()) && prevRequest.isOpen())
			pullRequestRef.set(prevRequest);
		else if (target.getBranch() != null || source.getBranch() != null)
			pullRequestRef.set(OneDev.getInstance(PullRequestManager.class).findEffective(target, source));
		
		if (pullRequestRef.get() == null) {
			ObjectId baseCommitId;
			if (target.getBranch() != null && source.getBranch() != null) {
				baseCommitId = GitUtils.getMergeBase(
						target.getProject().getRepository(), target.getObjectId(), 
						source.getProject().getRepository(), source.getObjectId());
			} else {
				baseCommitId = null;
			}
			if (baseCommitId != null) {
				PullRequest request = new PullRequest();
				request.setTitle(StringUtils.capitalize(source.getBranch().replace('-', ' ').replace('_', ' ').toLowerCase()));
				pullRequestRef.set(request);
				request.setTarget(target);
				request.setSource(source);
				request.setSubmitter(currentUser);
				
				request.setBaseCommitHash(baseCommitId.name());
				request.setHeadCommitHash(source.getObjectName());
				if (request.getBaseCommitHash().equals(source.getObjectName())) {
					CloseInfo closeInfo = new CloseInfo();
					closeInfo.setDate(new Date());
					closeInfo.setStatus(CloseInfo.Status.MERGED);
					request.setCloseInfo(closeInfo);
				}
	
				PullRequestUpdate update = new PullRequestUpdate();
				update.setDate(new DateTime(request.getSubmitDate()).plusSeconds(1).toDate());
				request.addUpdate(update);
				update.setRequest(request);
				update.setHeadCommitHash(request.getHeadCommitHash());
				update.setMergeBaseCommitHash(request.getBaseCommitHash());

				OneDev.getInstance(PullRequestManager.class).checkQuality(request);

				if (SecurityUtils.canWriteCode(getProject()) && request.getReviews().isEmpty()) {
					PullRequestReview review = new PullRequestReview();
					review.setRequest(request);
					review.setUser(SecurityUtils.getUser());
					request.getReviews().add(review);
				}
				request.setMergeStrategy(MergeStrategy.CREATE_MERGE_COMMIT);
			}
			
			requestModel = new LoadableDetachableModel<PullRequest>() {

				@Override
				protected PullRequest load() {
					if (pullRequestRef.get() != null) {
						pullRequestRef.get().setTarget(target);
						pullRequestRef.get().setSource(source);
						pullRequestRef.get().setSubmitter(SecurityUtils.getUser());
					}
					return pullRequestRef.get();
				}
				
			};
		} else {
			Long requestId = pullRequestRef.get().getId();
			requestModel = new LoadableDetachableModel<PullRequest>() {

				@Override
				protected PullRequest load() {
					return OneDev.getInstance(PullRequestManager.class).load(requestId);
				}
				
			};
		}
		requestModel.setObject(pullRequestRef.get());
		
	}
	
	private PullRequest getPullRequest() {
		return requestModel.getObject();
	}
	
	@Override
	protected void onInitialize() {
		super.onInitialize();

		setOutputMarkupId(true);
		
		add(new AffinalBranchPicker("target", target.getProjectId(), target.getBranch()) {

			@Override
			protected void onSelect(AjaxRequestTarget target, Project project, String branch) {
				PageParameters params = paramsOf(project, new ProjectAndBranch(project, branch), source); 
				
				/*
				 * Use below code instead of calling setResponsePage() to make sure the dropdown is 
				 * closed while creating the new page as otherwise clicking other places in original page 
				 * while new page is loading will result in ComponentNotFound issue for the dropdown 
				 * component
				 */
				String url = RequestCycle.get().urlFor(NewPullRequestPage.class, params).toString();
				target.appendJavaScript(String.format("window.location.href='%s';", url));
			}
			
		});
		
		if (target.getBranch() != null) {
			PageParameters params = CommitDetailPage.paramsOf(target.getProject(), target.getObjectName());
			Link<Void> targetCommitLink = new ViewStateAwarePageLink<Void>("targetCommitLink", CommitDetailPage.class, params);
			targetCommitLink.add(new Label("message", target.getCommit().getShortMessage()));
			add(targetCommitLink);
		} else {
			WebMarkupContainer targetCommitLink = new WebMarkupContainer("targetCommitLink");
			targetCommitLink.add(new WebMarkupContainer("message"));
			targetCommitLink.setVisible(false);
			add(targetCommitLink);
		}
		
		add(new AffinalBranchPicker("source", source.getProjectId(), source.getBranch()) {

			@Override
			protected void onSelect(AjaxRequestTarget target, Project project, String branch) {
				PageParameters params = paramsOf(getProject(), NewPullRequestPage.this.target,
						new ProjectAndBranch(project, branch)); 
				
				// Refer to comments in target branch picker for not using setResponsePage 
				String url = RequestCycle.get().urlFor(NewPullRequestPage.class, params).toString();
				target.appendJavaScript(String.format("window.location.href='%s';", url));
			}
			
		});
		
		if (source.getBranch() != null) {
			PageParameters params = CommitDetailPage.paramsOf(source.getProject(), source.getObjectName());
			Link<Void> sourceCommitLink = new ViewStateAwarePageLink<Void>("sourceCommitLink", CommitDetailPage.class, params);
			sourceCommitLink.add(new Label("message", source.getCommit().getShortMessage()));
			add(sourceCommitLink);
		} else {
			WebMarkupContainer sourceCommitLink = new WebMarkupContainer("sourceCommitLink");
			sourceCommitLink.add(new WebMarkupContainer("message"));
			sourceCommitLink.setVisible(false);
			add(sourceCommitLink);
		}
		
		add(new Link<Void>("swap") {

			@Override
			public void onClick() {
				PageParameters params = paramsOf(source.getProject(), source, target); 
				setResponsePage(NewPullRequestPage.class, params);
			}
			
		});
		
		Fragment fragment;
		PullRequest request = getPullRequest();
		if (target.getBranch() == null || source.getBranch() == null) 
			fragment = newBranchNotSpecifiedFrag();
		else if (request == null) 
			fragment = newUnrelatedHistoryFrag();
		else if (request.getId() != null && (request.isOpen() || !request.isMergeIntoTarget())) 
			fragment = newEffectiveFrag();
		else if (request.getSource().equals(request.getTarget())) 
			fragment = newSameBranchFrag();
		else if (request.isMerged()) 
			fragment = newAcceptedFrag();
		else 
			fragment = newCanSendFrag();
		add(fragment);

		if (getPullRequest() != null) {
			List<Tab> tabs = new ArrayList<>();
			
			tabs.add(new AjaxActionTab(Model.of("Commits")) {
				
				@Override
				protected void onSelect(AjaxRequestTarget target, Component tabLink) {
					Component panel = newCommitsPanel();
					getPage().replace(panel);
					target.add(panel);
				}
				
			});

			tabs.add(new AjaxActionTab(Model.of("Files")) {
				
				@Override
				protected void onSelect(AjaxRequestTarget target, Component tabLink) {
					Component panel = newRevDiffPanel();
					getPage().replace(panel);
					target.add(panel);
				}
				
			});

			add(new Tabbable(TABS_ID, tabs) {

				@Override
				protected void onConfigure() {
					super.onConfigure();
					setVisible(!getPullRequest().isMerged());
				}
				
			});
			
			add(newCommitsPanel());
		} else {
			add(new WebMarkupContainer(TABS_ID).setVisible(false));
			add(new WebMarkupContainer(TAB_PANEL_ID).setVisible(false));
		}
	}
	
	private Component newCommitsPanel() {
		return new CommitListPanel(TAB_PANEL_ID, null) {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(!getPullRequest().isMerged());
			}

			@Override
			protected CommitQuery getBaseQuery() {
				PullRequest request = getPullRequest();
				List<Revision> revisions = new ArrayList<>();
				revisions.add(new Revision(request.getBaseCommitHash(), Revision.Scope.SINCE));
				revisions.add(new Revision(request.getHeadCommitHash(), Revision.Scope.UNTIL));
				return new CommitQuery(Lists.newArrayList(new RevisionCriteria(revisions)));
			}

			@Override
			protected Project getProject() {
				return NewPullRequestPage.this.getProject();
			}

		}.setOutputMarkupId(true);
	}
	
	private RevisionDiffPanel newRevDiffPanel() {
		PullRequest request = getPullRequest();
		
		IModel<Project> projectModel = new LoadableDetachableModel<Project>() {

			@Override
			protected Project load() {
				Project project = source.getProject();
				project.cacheObjectId(source.getRevision(), 
						ObjectId.fromString(getPullRequest().getHeadCommitHash()));
				return project;
			}
			
		};
		
		IModel<String> blameModel = new IModel<String>() {

			@Override
			public void detach() {
			}

			@Override
			public String getObject() {
				return blameFile;
			}

			@Override
			public void setObject(String object) {
				blameFile = object;
			}
			
		};
		IModel<String> pathFilterModel = new IModel<String>() {

			@Override
			public void detach() {
			}

			@Override
			public String getObject() {
				return pathFilter;
			}

			@Override
			public void setObject(String object) {
				pathFilter = object;
			}
			
		};
		IModel<WhitespaceOption> whitespaceOptionModel = new IModel<WhitespaceOption>() {

			@Override
			public void detach() {
			}

			@Override
			public WhitespaceOption getObject() {
				return whitespaceOption;
			}

			@Override
			public void setObject(WhitespaceOption object) {
				whitespaceOption = object;
			}
			
		};

		/*
		 * we are passing source revision here instead of head commit hash of latest update
		 * as we want to preserve the branch name in case they are useful at some point 
		 * later. Also it is guaranteed to be resolved to the same commit has as we've cached
		 * it above when loading the project  
		 */
		RevisionDiffPanel diffPanel = new RevisionDiffPanel(TAB_PANEL_ID, projectModel, 
				new Model<PullRequest>(null), request.getBaseCommitHash(), 
				source.getRevision(), pathFilterModel, whitespaceOptionModel, blameModel, this) {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(!getPullRequest().isMerged());
			}
			
		};
		diffPanel.setOutputMarkupId(true);
		return diffPanel;
	}

	private Fragment newEffectiveFrag() {
		Fragment fragment = new Fragment("status", "effectiveFrag", this);

		fragment.add(new Label("description", new AbstractReadOnlyModel<String>() {

			@Override
			public String getObject() {
				if (requestModel.getObject().isOpen())
					return "This change is already opened for merge by pull request";
				else 
					return "This change is squashed/rebased onto base branch via pull request";
			}
			
		}).setEscapeModelStrings(false));
		
		fragment.add(new Link<Void>("link") {

			@Override
			protected void onInitialize() {
				super.onInitialize();
				add(new Label("label", new AbstractReadOnlyModel<String>() {

					@Override
					public String getObject() {
						return "#" + getPullRequest().getNumber();
					}
					
				}));
			}

			@Override
			public void onClick() {
				PageParameters params = PullRequestDetailPage.paramsOf(getPullRequest(), null);
				setResponsePage(PullRequestActivitiesPage.class, params);
			}
			
		});
		
		return fragment;
	}
	
	private Fragment newSameBranchFrag() {
		return new Fragment("status", "sameBranchFrag", this);
	}
	
	private Fragment newUnrelatedHistoryFrag() {
		return new Fragment("status", "unrelatedHistoryFrag", this);
	}
	
	private Fragment newBranchNotSpecifiedFrag() {
		return new Fragment("status", "branchNotSpecifiedFrag", this);
	}
	
	private Fragment newAcceptedFrag() {
		Fragment fragment = new Fragment("status", "mergedFrag", this);
		fragment.add(new BranchLink("sourceBranch", getPullRequest().getSource(), null));
		fragment.add(new BranchLink("targetBranch", getPullRequest().getTarget(), null));
		fragment.add(new Link<Void>("swapBranches") {

			@Override
			public void onClick() {
				setResponsePage(
						NewPullRequestPage.class, 
						paramsOf(getProject(), getPullRequest().getSource(), getPullRequest().getTarget()));
			}
			
		});
		return fragment;
	}
	
	@Override
	protected String getRobotsMeta() {
		return "noindex,nofollow";
	}
	
	private Fragment newCanSendFrag() {
		Fragment fragment = new Fragment("status", "canSendFrag", this);
		Form<?> form = new Form<Void>("form");
		fragment.add(form);
		
		form.add(new Button("send") {

			@Override
			public void onSubmit() {
				super.onSubmit();

				Dao dao = OneDev.getInstance(Dao.class);
				ProjectAndBranch target = getPullRequest().getTarget();
				ProjectAndBranch source = getPullRequest().getSource();
				if (!target.getObjectName().equals(getPullRequest().getTarget().getObjectName()) 
						|| !source.getObjectName().equals(getPullRequest().getSource().getObjectName())) {
					getSession().warn("Either target branch or source branch has new commits just now, please re-check.");
					setResponsePage(NewPullRequestPage.class, paramsOf(getProject(), target, source));
				} else {
					getPullRequest().setSource(source);
					getPullRequest().setTarget(target);
					for (PullRequestReview review: getPullRequest().getReviews())
						review.setUser(dao.load(User.class, review.getUser().getId()));
					
					OneDev.getInstance(PullRequestManager.class).open(getPullRequest());
					
					setResponsePage(PullRequestActivitiesPage.class, PullRequestActivitiesPage.paramsOf(getPullRequest(), null));
				}			
				
			}
		});
		
		WebMarkupContainer titleContainer = new WebMarkupContainer("title");
		form.add(titleContainer);
		TextField<String> titleInput = new TextField<String>("title", new IModel<String>() {

			@Override
			public void detach() {
			}

			@Override
			public String getObject() {
				return getPullRequest().getTitle();
			}

			@Override
			public void setObject(String object) {
				getPullRequest().setTitle(object);
			}
			
		});
		titleInput.add(new ReferenceInputBehavior(false) {
			
			@Override
			protected Project getProject() {
				return NewPullRequestPage.this.getProject();
			}
			
		});
		titleInput.setRequired(true).setLabel(Model.of("Title"));
		titleContainer.add(titleInput);
		
		titleContainer.add(new FencedFeedbackPanel("feedback", titleInput));
		
		titleContainer.add(AttributeAppender.append("class", new AbstractReadOnlyModel<String>() {

			@Override
			public String getObject() {
				return !titleInput.isValid()?" has-error":"";
			}
			
		}));

		form.add(new CommentInput("comment", new IModel<String>() {

			@Override
			public void detach() {
			}

			@Override
			public String getObject() {
				return getPullRequest().getDescription();
			}

			@Override
			public void setObject(String object) {
				getPullRequest().setDescription(object);
			}
			
		}, false) {

			@Override
			protected AttachmentSupport getAttachmentSupport() {
				return new ProjectAttachmentSupport(getProject(), getPullRequest().getUUID());
			}

			@Override
			protected Project getProject() {
				return NewPullRequestPage.this.getProject();
			}
			
		});

		form.add(newMergeStrategyContainer());
		
		form.add(new ReviewListPanel("reviewers", requestModel));
		
		return fragment;
	}
	
	private Component newMergeStrategyContainer() {
		WebMarkupContainer container = new WebMarkupContainer("mergeStrategy");
		
		IModel<MergeStrategy> mergeStrategyModel = new IModel<MergeStrategy>() {

			@Override
			public void detach() {
			}

			@Override
			public MergeStrategy getObject() {
				return getPullRequest().getMergeStrategy();
			}

			@Override
			public void setObject(MergeStrategy object) {
				getPullRequest().setMergeStrategy(object);
			}
			
		};
		
		List<MergeStrategy> mergeStrategies = Arrays.asList(MergeStrategy.values());
		DropDownChoice<MergeStrategy> mergeStrategyDropdown = 
				new DropDownChoice<MergeStrategy>("select", mergeStrategyModel, mergeStrategies);

		mergeStrategyDropdown.add(new OnChangeAjaxBehavior() {
			
			@Override
			protected void onUpdate(AjaxRequestTarget target) {
				Component newContainer = newMergeStrategyContainer();
				container.replaceWith(newContainer);
				target.add(newContainer);
			}
			
		});
		
		container.add(mergeStrategyDropdown);
		
		container.add(new Label("help", getPullRequest().getMergeStrategy().getDescription()));
		
		container.add(new AjaxLazyLoadPanel("status") {
			
			@Override
			protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
				super.updateAjaxAttributes(attributes);
				attributes.getAjaxCallListeners().add(new DisableGlobalLoadingIndicatorListener());
			}

			@Override
			public Component getLazyLoadComponent(String componentId) {
				PullRequest request = getPullRequest();
				MergePreview mergePreview = new MergePreview(request.getTarget().getObjectName(), 
						request.getHeadCommitHash(), request.getMergeStrategy(), null);
				ObjectId merged = mergePreview.getMergeStrategy().merge(request);
				if (merged != null)
					mergePreview.setMerged(merged.name());
				request.setLastMergePreview(mergePreview);
				
				if (merged != null) {
					Component result = new Label(componentId, "<i class=\"fa fa-check-circle\"></i> Able to merge without conflicts");
					result.add(AttributeAppender.append("class", "no-conflict"));
					result.setEscapeModelStrings(false);
					return result;
				} else { 
					Component result = new Label(componentId, 
							"<i class=\"fa fa-warning\"></i> There are merge conflicts. You can still create the pull request though");
					result.add(AttributeAppender.append("class", "conflict"));
					result.setEscapeModelStrings(false);
					return result;
				}
			}

			@Override
			public Component getLoadingComponent(String markupId) {
				Component component = new Label(markupId, "<img src='/img/ajax-indicator-big.gif'></img> Calculating merge preview...");
				component.add(AttributeAppender.append("class", "calculating"));
				component.setEscapeModelStrings(false);
				return component;
			}
			
		});
		
		container.setOutputMarkupId(true);		
		
		return container;
	}

	@Override
	public void renderHead(IHeaderResponse response) {
		super.renderHead(response);
		response.render(CssHeaderItem.forReference(new NewPullRequestResourceReference()));
	}

	@Override
	protected void onDetach() {
		requestModel.detach();
		
		super.onDetach();
	}

	@Override
	public MarkPos getMark() {
		return mark;
	}

	@Override
	public String getMarkUrl(MarkPos mark) {
		RevisionComparePage.State state = new RevisionComparePage.State();
		state.mark = mark;
		state.leftSide = new ProjectAndBranch(source.getProject(), getPullRequest().getBaseCommitHash());
		state.rightSide = new ProjectAndBranch(source.getProject(), getPullRequest().getHeadCommitHash());
		state.pathFilter = pathFilter;
		state.tabPanel = RevisionComparePage.TabPanel.FILE_CHANGES;
		state.whitespaceOption = whitespaceOption;
		state.compareWithMergeBase = false;
		return urlFor(RevisionComparePage.class, RevisionComparePage.paramsOf(source.getProject(), state)).toString();
	}

	@Override
	public void onMark(AjaxRequestTarget target, MarkPos mark) {
		this.mark = mark;
	}

	@Override
	public CodeComment getOpenComment() {
		if (commentId != null)
			return OneDev.getInstance(CodeCommentManager.class).load(commentId);
		else
			return null;
	}

	@Override
	public void onAddComment(AjaxRequestTarget target, MarkPos mark) {
		this.commentId = null;
		this.mark = mark;
	}

	@Override
	public void onCommentOpened(AjaxRequestTarget target, CodeComment comment) {
		if (comment != null) {
			commentId = comment.getId();
			mark = comment.getMarkPos();
		} else {
			commentId = null;
		}
	}

	@Override
	protected boolean isPermitted() {
		return SecurityUtils.canReadCode(getProject()) && SecurityUtils.canReadCode(source.getProject());
	}
	
	@Override
	public String getCommentUrl(CodeComment comment) {
		RevisionComparePage.State state = new RevisionComparePage.State();
		mark = comment.getMarkPos();
		state.commentId = comment.getId();
		state.leftSide = new ProjectAndBranch(source.getProject(), getPullRequest().getBaseCommitHash());
		state.rightSide = new ProjectAndBranch(source.getProject(), getPullRequest().getHeadCommitHash());
		state.pathFilter = pathFilter;
		state.tabPanel = RevisionComparePage.TabPanel.FILE_CHANGES;
		state.whitespaceOption = whitespaceOption;
		state.compareWithMergeBase = false;
		return urlFor(RevisionComparePage.class, RevisionComparePage.paramsOf(source.getProject(), state)).toString();
	}

}
