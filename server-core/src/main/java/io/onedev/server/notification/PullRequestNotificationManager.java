package io.onedev.server.notification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import io.onedev.commons.launcher.loader.Listen;
import io.onedev.server.entitymanager.PullRequestWatchManager;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.entitymanager.UrlManager;
import io.onedev.server.entitymanager.UserManager;
import io.onedev.server.event.MarkdownAware;
import io.onedev.server.event.entity.EntityPersisted;
import io.onedev.server.event.pullrequest.PullRequestBuildEvent;
import io.onedev.server.event.pullrequest.PullRequestChangeEvent;
import io.onedev.server.event.pullrequest.PullRequestCodeCommentCreated;
import io.onedev.server.event.pullrequest.PullRequestCodeCommentReplied;
import io.onedev.server.event.pullrequest.PullRequestCommented;
import io.onedev.server.event.pullrequest.PullRequestEvent;
import io.onedev.server.event.pullrequest.PullRequestMergePreviewCalculated;
import io.onedev.server.event.pullrequest.PullRequestOpened;
import io.onedev.server.event.pullrequest.PullRequestUpdated;
import io.onedev.server.infomanager.UserInfoManager;
import io.onedev.server.model.PullRequest;
import io.onedev.server.model.PullRequestAssignment;
import io.onedev.server.model.PullRequestReview;
import io.onedev.server.model.PullRequestWatch;
import io.onedev.server.model.User;
import io.onedev.server.model.support.NamedQuery;
import io.onedev.server.model.support.QuerySetting;
import io.onedev.server.model.support.pullrequest.changedata.PullRequestApproveData;
import io.onedev.server.model.support.pullrequest.changedata.PullRequestChangeData;
import io.onedev.server.model.support.pullrequest.changedata.PullRequestDiscardData;
import io.onedev.server.model.support.pullrequest.changedata.PullRequestMergeData;
import io.onedev.server.model.support.pullrequest.changedata.PullRequestReopenData;
import io.onedev.server.model.support.pullrequest.changedata.PullRequestRequestedForChangesData;
import io.onedev.server.persistence.annotation.Transactional;
import io.onedev.server.search.entity.EntityQuery;
import io.onedev.server.search.entity.QueryWatchBuilder;
import io.onedev.server.search.entity.pullrequest.PullRequestQuery;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.util.markdown.MarkdownManager;
import io.onedev.server.util.markdown.MentionParser;

@Singleton
public class PullRequestNotificationManager extends AbstractNotificationManager {
	
	private final MailManager mailManager;
	
	private final UrlManager urlManager;
	
	private final PullRequestWatchManager pullRequestWatchManager;
	
	private final UserInfoManager userInfoManager;
	
	private final UserManager userManager;
	
	private final SettingManager settingManager;
	
	@Inject
	public PullRequestNotificationManager(MailManager mailManager, UrlManager urlManager, 
			MarkdownManager markdownManager, PullRequestWatchManager pullRequestWatchManager, 
			UserInfoManager userInfoManager, UserManager userManager, SettingManager settingManager) {
		super(markdownManager);
		this.mailManager = mailManager;
		this.urlManager = urlManager;
		this.pullRequestWatchManager = pullRequestWatchManager;
		this.userInfoManager = userInfoManager;
		this.userManager = userManager;
		this.settingManager = settingManager;
	}
	
	@Transactional
	@Listen
	public void on(PullRequestEvent event) {
		PullRequest request = event.getRequest();
		User user = event.getUser();
		
		String url;
		if (event instanceof PullRequestCommented)
			url = urlManager.urlFor(((PullRequestCommented)event).getComment());
		else if (event instanceof PullRequestChangeEvent) 
			url = urlManager.urlFor(((PullRequestChangeEvent)event).getChange());
		else if (event instanceof PullRequestCodeCommentCreated)
			url = urlManager.urlFor(((PullRequestCodeCommentCreated)event).getComment(), request);
		else if (event instanceof PullRequestCodeCommentReplied)
			url = urlManager.urlFor(((PullRequestCodeCommentReplied)event).getReply(), request);
		else 
			url = urlManager.urlFor(request);
		
		for (Map.Entry<User, Boolean> entry: new QueryWatchBuilder<PullRequest>() {

			@Override
			protected PullRequest getEntity() {
				return request;
			}

			@Override
			protected Collection<? extends QuerySetting<?>> getQuerySettings() {
				return request.getTargetProject().getUserPullRequestQuerySettings();
			}

			@Override
			protected EntityQuery<PullRequest> parse(String queryString) {
				return PullRequestQuery.parse(request.getTargetProject(), queryString);
			}

			@Override
			protected Collection<? extends NamedQuery> getNamedQueries() {
				return request.getTargetProject().getPullRequestSetting().getNamedQueries(true);
			}
			
		}.getWatches().entrySet()) {
			pullRequestWatchManager.watch(request, entry.getKey(), entry.getValue());
		}
		
		for (Map.Entry<User, Boolean> entry: new QueryWatchBuilder<PullRequest>() {

			@Override
			protected PullRequest getEntity() {
				return request;
			}

			@Override
			protected Collection<? extends QuerySetting<?>> getQuerySettings() {
				return userManager.query().stream().map(it->it.getPullRequestQuerySetting()).collect(Collectors.toList());
			}

			@Override
			protected EntityQuery<PullRequest> parse(String queryString) {
				return PullRequestQuery.parse(null, queryString);
			}

			@Override
			protected Collection<? extends NamedQuery> getNamedQueries() {
				return settingManager.getPullRequestSetting().getNamedQueries();
			}
			
		}.getWatches().entrySet()) {
			pullRequestWatchManager.watch(request, entry.getKey(), entry.getValue());
		}
		
		Collection<User> notifiedUsers = Sets.newHashSet();
		if (user != null) {
			notifiedUsers.add(user); // no need to notify the user generating the event
			if (!user.isSystem())
				pullRequestWatchManager.watch(request, user, true);
		}
		
		User committer = null;
		if (event instanceof PullRequestUpdated) {
			PullRequestUpdated pullRequestUpdated = (PullRequestUpdated) event;
			Collection<User> committers = pullRequestUpdated.getCommitters();
			if (committers.size() == 1) {
				committer = committers.iterator().next();
				notifiedUsers.add(committer);
			}
			for (User each: committers) {
				if (!each.isSystem())
					pullRequestWatchManager.watch(request, each, true);
			}
		}
		
		if (event instanceof PullRequestOpened) {
			for (PullRequestReview review: request.getReviews()) {
				if (review.getResult() == null) {
					// reviewers will be sent review invitation separately 
					notifiedUsers.add(review.getUser());
				}
			}
		}
		
		String replyAddress = mailManager.getReplyAddressForPullRequest(request);
		String threadingReferences = getThreadingReferences(request);
		if (event instanceof PullRequestChangeEvent 
				&& request.getSubmitter() != null 
				&& !notifiedUsers.contains(request.getSubmitter())) {
			PullRequestChangeEvent changeEvent = (PullRequestChangeEvent) event;
			PullRequestChangeData changeData = changeEvent.getChange().getData();
			String subject = null;
			if (changeData instanceof PullRequestApproveData) 
				subject = String.format(user.getDisplayName() + " approved pull request %s", request.getNumberAndTitle());
			else if (changeData instanceof PullRequestRequestedForChangesData) 
				subject = String.format(user.getDisplayName() + " requested changes for pull request %s", request.getNumberAndTitle());
			else if (changeData instanceof PullRequestDiscardData) 
				subject = String.format(user.getDisplayName() + " discarded pull request %s", request.getNumberAndTitle());
			if (subject != null) { 
				mailManager.sendMailAsync(Lists.newArrayList(request.getSubmitter().getEmail()), 
						Lists.newArrayList(), subject, getHtmlBody(event, url), getTextBody(event, url), 
						replyAddress, threadingReferences);
				notifiedUsers.add(request.getSubmitter());
			}
		}
		
		Collection<User> mentionedUsers = new HashSet<>();
		if (event instanceof MarkdownAware) {
			MarkdownAware markdownAware = (MarkdownAware) event;
			String markdown = markdownAware.getMarkdown();
			if (markdown != null) {
				String rendered = markdownManager.render(markdown);
				
				for (String userName: new MentionParser().parseMentions(rendered)) {
					User mentionedUser = userManager.findByName(userName);
					if (mentionedUser != null && notifiedUsers.add(mentionedUser)) { 
						pullRequestWatchManager.watch(request, mentionedUser, true);
						mentionedUsers.add(mentionedUser);
					}
				}
			}
		} 
		
		boolean notifyWatchers = false;
		if (event instanceof PullRequestChangeEvent) {
			PullRequestChangeData changeData = ((PullRequestChangeEvent) event).getChange().getData();
			if (changeData instanceof PullRequestApproveData 
					|| changeData instanceof PullRequestRequestedForChangesData 
					|| changeData instanceof PullRequestMergeData 
					|| changeData instanceof PullRequestDiscardData
					|| changeData instanceof PullRequestReopenData) {
				notifyWatchers = true;
			}
		} else if (!(event instanceof PullRequestMergePreviewCalculated || event instanceof PullRequestBuildEvent)) {
			notifyWatchers = true;
		}
		
		if (!mentionedUsers.isEmpty() || notifyWatchers) {
			Collection<User> ccUsers = new HashSet<>();
			
			Collection<String> notifiedEmailAddresses;
			if (event instanceof PullRequestCommented)
				notifiedEmailAddresses = ((PullRequestCommented) event).getNotifiedEmailAddresses();
			else
				notifiedEmailAddresses = new ArrayList<>();
			for (PullRequestWatch watch: request.getWatches()) {
				Date visitDate = userInfoManager.getPullRequestVisitDate(watch.getUser(), request);
				if (watch.isWatching() 
						&& (visitDate == null || visitDate.before(event.getDate())) 
						&& (!(event instanceof PullRequestUpdated) || !watch.getUser().equals(request.getSubmitter()))
						&& !notifiedUsers.contains(watch.getUser())
						&& !notifiedEmailAddresses.stream().anyMatch(watch.getUser().getEmails()::contains)) {
					ccUsers.add(watch.getUser());
				}
			}

			if (!mentionedUsers.isEmpty() || !ccUsers.isEmpty()) {
				String subject;
				if (user != null) 
					subject = String.format("%s %s", user.getDisplayName(), event.getActivity(true));
				else if (committer != null) 
					subject = String.format("%s added commits to pull request %s", committer.getDisplayName(), request.getNumberAndTitle());
				else
					subject = event.getActivity(true);
				
				mailManager.sendMailAsync(
						mentionedUsers.stream().map(User::getEmail).collect(Collectors.toList()),
						ccUsers.stream().map(User::getEmail).collect(Collectors.toList()), 
						subject, getHtmlBody(event, url), getTextBody(event, url), replyAddress, threadingReferences);
			}
		}				
	}
	
	private String getThreadingReferences(PullRequest request) {
		return request.getUUID() + "@onedev";
	}
	
	@Transactional
	@Listen
	public void on(EntityPersisted event) {
		if (event.isNew()) {
			if (event.getEntity() instanceof PullRequestReview) {
				PullRequestReview review = (PullRequestReview) event.getEntity();
				PullRequest request = review.getRequest();
				if (review.getResult() == null && !review.getUser().equals(SecurityUtils.getUser())) {
					pullRequestWatchManager.watch(request, review.getUser(), true);
					String url = urlManager.urlFor(request);
					String subject = String.format("You are invited to review pull request %s", request.getNumberAndTitle());
					String replyAddress = mailManager.getReplyAddressForPullRequest(request);
					mailManager.sendMailAsync(Lists.newArrayList(review.getUser().getEmail()), Lists.newArrayList(),
							subject, getHtmlBody(event, url), getTextBody(event, url), replyAddress, 
							getThreadingReferences(request));
				}
			} else if (event.getEntity() instanceof PullRequestAssignment) {
				PullRequestAssignment assignment = (PullRequestAssignment) event.getEntity();
				PullRequest request = assignment.getRequest();
				if (!assignment.getUser().equals(SecurityUtils.getUser())) {
					pullRequestWatchManager.watch(request, assignment.getUser(), true);
					String url = urlManager.urlFor(request);
					String subject = String.format("You are assigned and expected to merge pull request %s", 
							request.getNumberAndTitle());
					String replyAddress = mailManager.getReplyAddressForPullRequest(request);
					mailManager.sendMailAsync(Lists.newArrayList(assignment.getUser().getEmail()), Lists.newArrayList(),
							subject, getHtmlBody(event, url), getTextBody(event, url), replyAddress, 
							getThreadingReferences(request));
				}
			}
		}
	}

} 
