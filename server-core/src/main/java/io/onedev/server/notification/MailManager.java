package io.onedev.server.notification;

import java.util.Collection;

import javax.annotation.Nullable;

import io.onedev.server.model.Issue;
import io.onedev.server.model.PullRequest;
import io.onedev.server.model.support.administration.MailSetting;

public interface MailManager {
	
	public static final String TEST_SUB_ADDRESSING = "test-sub-addressing";
	
	void sendMail(Collection<String> toList, Collection<String> ccList, String subject, 
			String htmlBody, String textBody, @Nullable String replyAddress, @Nullable String references);
	
	void sendMail(MailSetting mailSetting, Collection<String> toList, Collection<String> ccList, 
			String subject, String htmlBody, String textBody, @Nullable String replyAddress, 
			@Nullable String references);
	
	void sendMailAsync(Collection<String> toList, Collection<String> ccList, String subject, 
			String htmlBody, String textBody, @Nullable String replyAddress, @Nullable String references);
	
	@Nullable
	String getReplyAddressForIssue(Issue issue);
	
	@Nullable
	String getReplyAddressForPullRequest(PullRequest request);
	
	InboxMonitor monitorInbox(MailSetting mailSetting, MessageListener listener);
	
}
