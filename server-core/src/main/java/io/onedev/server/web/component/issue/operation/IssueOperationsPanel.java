package io.onedev.server.web.component.issue.operation;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.apache.wicket.AttributeModifier;
import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.ajax.markup.html.form.AjaxButton;
import org.apache.wicket.behavior.AttributeAppender;
import org.apache.wicket.core.request.handler.IPartialPageRequestHandler;
import org.apache.wicket.markup.head.CssHeaderItem;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.markup.repeater.RepeatingView;
import org.apache.wicket.model.AbstractReadOnlyModel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.model.PropertyModel;
import org.apache.wicket.request.cycle.RequestCycle;

import com.google.common.collect.Lists;

import io.onedev.commons.utils.StringUtils;
import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.IssueChangeManager;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.entitymanager.UserManager;
import io.onedev.server.issue.TransitionSpec;
import io.onedev.server.issue.fieldspec.DateField;
import io.onedev.server.issue.fieldspec.FieldSpec;
import io.onedev.server.issue.transitiontrigger.PressButtonTrigger;
import io.onedev.server.model.Issue;
import io.onedev.server.model.Project;
import io.onedev.server.model.User;
import io.onedev.server.model.support.administration.GlobalIssueSetting;
import io.onedev.server.search.entity.issue.IssueQuery;
import io.onedev.server.search.entity.issue.IssueQueryLexer;
import io.onedev.server.util.Input;
import io.onedev.server.util.IssueUtils;
import io.onedev.server.util.criteria.Criteria;
import io.onedev.server.web.behavior.WebSocketObserver;
import io.onedev.server.web.component.issue.IssueStateLabel;
import io.onedev.server.web.component.markdown.AttachmentSupport;
import io.onedev.server.web.component.project.comment.CommentInput;
import io.onedev.server.web.component.sideinfo.SideInfoLink;
import io.onedev.server.web.editable.BeanContext;
import io.onedev.server.web.editable.BeanEditor;
import io.onedev.server.web.util.ProjectAttachmentSupport;

@SuppressWarnings("serial")
public abstract class IssueOperationsPanel extends Panel {

	private static final String ACTION_OPTIONS_ID = "actionOptions";
	
	public IssueOperationsPanel(String id) {
		super(id);
	}

	private void newEmptyActionOptions(@Nullable AjaxRequestTarget target) {
		WebMarkupContainer actionOptions = new WebMarkupContainer(ACTION_OPTIONS_ID);
		actionOptions.setOutputMarkupPlaceholderTag(true);
		actionOptions.setVisible(false);
		if (target != null) {
			replace(actionOptions);
			target.add(actionOptions);
		} else {
			addOrReplace(actionOptions);
		}
	}
	
	@Override
	protected void onBeforeRender() {
		WebMarkupContainer stateContainer = new WebMarkupContainer("state");
		addOrReplace(stateContainer);
		stateContainer.add(new IssueStateLabel("state", new AbstractReadOnlyModel<Issue>() {

			@Override
			public Issue getObject() {
				return getIssue();
			}
			
		}));
		
		RepeatingView transitionsView = new RepeatingView("transitions");

		GlobalIssueSetting issueSetting = OneDev.getInstance(SettingManager.class).getIssueSetting();
		List<TransitionSpec> transitions = getIssue().getProject().getIssueSetting().getTransitionSpecs(true);
		for (TransitionSpec transition: transitions) {
			if (transition.canTransitManually(getIssue(), null)) {
				PressButtonTrigger trigger = (PressButtonTrigger) transition.getTrigger();
				AjaxLink<Void> link = new AjaxLink<Void>(transitionsView.newChildId()) {

					private String comment;
					
					@Override
					public void onClick(AjaxRequestTarget target) {
						Fragment fragment = new Fragment(ACTION_OPTIONS_ID, "transitionFrag", IssueOperationsPanel.this);
						Class<?> fieldBeanClass = IssueUtils.defineFieldBeanClass(getIssue().getProject());
						Serializable fieldBean = getIssue().getFieldBean(fieldBeanClass, true);

						Form<?> form = new Form<Void>("form") {

							@Override
							protected void onError() {
								super.onError();
								RequestCycle.get().find(AjaxRequestTarget.class).add(this);
							}
							
						};
						
						Collection<String> propertyNames = IssueUtils.getPropertyNames(getIssue().getProject(), 
								fieldBeanClass, trigger.getPromptFields());
						BeanEditor editor = BeanContext.edit("fields", fieldBean, propertyNames, false); 
						form.add(editor);
						
						form.add(new CommentInput("comment", new PropertyModel<String>(this, "comment"), false) {

							@Override
							protected AttachmentSupport getAttachmentSupport() {
								return new ProjectAttachmentSupport(getProject(), getIssue().getUUID());
							}

							@Override
							protected Project getProject() {
								return getIssue().getProject();
							}
							
							@Override
							protected List<AttributeModifier> getInputModifiers() {
								return Lists.newArrayList(AttributeModifier.replace("placeholder", "Leave a comment"));
							}
							
							@Override
							protected List<User> getMentionables() {
								return OneDev.getInstance(UserManager.class).queryAndSort(getIssue().getParticipants());
							}
							
						});

						form.add(new AjaxButton("save") {

							@Override
							protected void onSubmit(AjaxRequestTarget target, Form<?> form) {
								super.onSubmit(target, form);

								getIssue().removeFields(transition.getRemoveFields());
								Map<String, Object> fieldValues = IssueUtils.getFieldValues(
										editor.newComponentContext(), fieldBean, trigger.getPromptFields());
								IssueChangeManager manager = OneDev.getInstance(IssueChangeManager.class);
								manager.changeState(getIssue(), transition.getToState(), fieldValues, comment);
								target.add(IssueOperationsPanel.this);
							}
							
						});
						
						form.add(new AjaxLink<Void>("cancel") {

							@Override
							public void onClick(AjaxRequestTarget target) {
								newEmptyActionOptions(target);
							}
							
						});
						fragment.add(form);
						
						fragment.setOutputMarkupId(true);
						IssueOperationsPanel.this.replace(fragment);
						target.add(fragment);
					}
					
				};
				link.add(new Label("label", trigger.getButtonLabel()));
				transitionsView.add(link);
			}
		}
		
		addOrReplace(transitionsView);

		List<String> criterias = new ArrayList<>();
		if (getIssue().getMilestone() != null) {
			criterias.add(Criteria.quote(Issue.FIELD_MILESTONE) + " " 
					+ IssueQuery.getRuleName(IssueQueryLexer.Is) + " " 
					+ Criteria.quote(getIssue().getMilestoneName()));
		}
		for (Map.Entry<String, Input> entry: getIssue().getFieldInputs().entrySet()) {
			if (getIssue().isFieldVisible(entry.getKey())) {
				List<String> strings = entry.getValue().getValues();
				if (strings.isEmpty()) {
					criterias.add(Criteria.quote(entry.getKey()) + " " + IssueQuery.getRuleName(IssueQueryLexer.IsEmpty));
				} else { 
					FieldSpec field = issueSetting.getFieldSpec(entry.getKey());
					if (field.isAllowMultiple()) {
						for (String string: strings) {
							criterias.add(Criteria.quote(entry.getKey()) + " " 
									+ IssueQuery.getRuleName(IssueQueryLexer.Is) + " " 
									+ Criteria.quote(string));
						}
					} else if (!(field instanceof DateField)) { 
						criterias.add(Criteria.quote(entry.getKey()) + " " 
								+ IssueQuery.getRuleName(IssueQueryLexer.Is) + " " 
								+ Criteria.quote(strings.iterator().next()));
					}
				}
			}
		}

		String query;
		if (!criterias.isEmpty())
			query = StringUtils.join(criterias, " and ");
		else
			query = null;
		
		Component createIssueButton = newCreateIssueButton("newIssue", query);
		addOrReplace(createIssueButton);
		
		stateContainer.add(AttributeAppender.append("class", new LoadableDetachableModel<String>() {

			@Override
			protected String load() {
				createIssueButton.configure();
				if (createIssueButton.isVisible() || transitionsView.size() != 0)
					return "with-separator";
				else
					return "";
			}
			
		}));
		
		addOrReplace(new SideInfoLink("moreInfo"));
		
		newEmptyActionOptions(null);
		
		super.onBeforeRender();
	}
	
	@Override
	protected void onInitialize() {
		super.onInitialize();
		
		add(new WebSocketObserver() {
			
			@Override
			public void onObservableChanged(IPartialPageRequestHandler handler, String observable) {
				handler.add(IssueOperationsPanel.this);
			}
			
			@Override
			public void onConnectionOpened(IPartialPageRequestHandler handler) {
				handler.add(IssueOperationsPanel.this);
			}
			
			@Override
			public Collection<String> getObservables() {
				return Lists.newArrayList(Issue.getWebSocketObservable(getIssue().getId()));
			}
			
		});
		
		setOutputMarkupId(true);
	}

	@Override
	public void renderHead(IHeaderResponse response) {
		super.renderHead(response);
		response.render(CssHeaderItem.forReference(new IssueOperationsCssResourceReference()));
	}

	protected abstract Issue getIssue();
	
	protected abstract Component newCreateIssueButton(String componentId, String templateQuery);
	
}
