package io.onedev.server.model.support.administration;

import java.io.Serializable;

import org.hibernate.validator.constraints.NotEmpty;

import io.onedev.server.util.patternset.PatternSet;
import io.onedev.server.util.usage.Usage;
import io.onedev.server.web.editable.annotation.Editable;
import io.onedev.server.web.editable.annotation.Password;

@Editable
public class MailSetting implements Serializable {
	
	private static final long serialVersionUID = 1L;

	private String smtpHost;
	
	private int smtpPort = 587;
	
	private String smtpUser;
	
	private String smtpPassword;
	
	private String emailAddress;
	
	private ReceiveMailSetting receiveMailSetting;
	
	private boolean enableStartTLS = true;
	
	private int timeout = 60;

	@Editable(order=100, name="SMTP Host")
	@NotEmpty
	public String getSmtpHost() {
		return smtpHost;
	}

	public void setSmtpHost(String smtpHost) {
		this.smtpHost = smtpHost;
	}

	@Editable(order=200, name="SMTP Port")
	public int getSmtpPort() {
		return smtpPort;
	}

	public void setSmtpPort(int smtpPort) {
		this.smtpPort = smtpPort;
	}

	@Editable(order=300, name="SMTP User", description=
		"Optionally specify user name here if the SMTP host needs authentication"
		)
	public String getSmtpUser() {
		return smtpUser;
	}

	public void setSmtpUser(String smtpUser) {
		this.smtpUser = smtpUser;
	}

	@Editable(order=400, name="SMTP Password", description=
		"Optionally specify password here if the SMTP host needs authentication"
		)
	@Password(autoComplete="new-password")
	public String getSmtpPassword() {
		return smtpPassword;
	}

	public void setSmtpPassword(String smtpPassword) {
		this.smtpPassword = smtpPassword;
	}

	@Editable(order=410, name="System Email Address", description="This email address will be used as sender "
			+ "address for various notifications")
	@NotEmpty
	public String getEmailAddress() {
		return emailAddress;
	}

	public void setEmailAddress(String emailAddress) {
		this.emailAddress = emailAddress;
	}

	@Editable(order=450, name="Check Incoming Email", description="Enable this to check inbox of "
			+ "system email address to take various actions, such as creating issue, "
			+ "post issue/pull request comments etc.<br>"
			+ "<b class='text-danger'>NOTE:</b> <a href='https://en.wikipedia.org/wiki/Email_address#Subaddressing' target='_blank'>Sub addressing</a> "
			+ "needs to be enabled for your mail server, as OneDev needs to use this to track context of sent email and received email")
	public ReceiveMailSetting getReceiveMailSetting() {
		return receiveMailSetting;
	}

	public void setReceiveMailSetting(ReceiveMailSetting receiveMailSetting) {
		this.receiveMailSetting = receiveMailSetting;
	}

	@Editable(order=550, name="Enable STARTTLS", description="Whether or not to enable STARTTLS "
			+ "when interacting with mail server")
	public boolean isEnableStartTLS() {
		return enableStartTLS;
	}

	public void setEnableStartTLS(boolean enableStartTLS) {
		this.enableStartTLS = enableStartTLS;
	}

	@Editable(order=600, description="Specify timeout in seconds when communicating with mail server. " +
			"Use 0 to set an infinite timeout.")
	public int getTimeout() {
		return timeout;
	}

	public void setTimeout(int timeout) {
		this.timeout = timeout;
	}
	
	public void onRenameRole(String oldName, String newName) {
		if (receiveMailSetting != null) {
			for (SenderAuthorization authorization: receiveMailSetting.getSenderAuthorizations()) {
				if (authorization.getAuthorizedRoleName().equals(oldName))
					authorization.setAuthorizedRoleName(newName);
			}
		}
	}
	
	public Usage onDeleteRole(String roleName) {
		Usage usage = new Usage();
		if (getReceiveMailSetting() != null) {
			int index = 0;
			for (SenderAuthorization authorization: getReceiveMailSetting().getSenderAuthorizations()) {
				if (authorization.getAuthorizedRoleName().equals(roleName))
					usage.add("authorized role");
				usage.prefix("sender authorization #" + index);
				index++;
			}
			usage.prefix("check incoming email");
		} 
		return usage;
	}
	
	public void onRenameProject(String oldName, String newName) {
		if (receiveMailSetting != null) {
			for (SenderAuthorization authorization: receiveMailSetting.getSenderAuthorizations()) {
				if (authorization.getDefaultProject().equals(oldName))
					authorization.setDefaultProject(newName);
				PatternSet patternSet = PatternSet.parse(authorization.getAuthorizedProjects());
				if (patternSet.getIncludes().remove(oldName))
					patternSet.getIncludes().add(newName);
				if (patternSet.getExcludes().remove(oldName))
					patternSet.getExcludes().add(newName);
				authorization.setAuthorizedProjects(patternSet.toString());
				if (authorization.getAuthorizedProjects().length() == 0)
					authorization.setAuthorizedProjects(null);
			}
		}
	}
	
	public Usage onDeleteProject(String projectName) {
		Usage usage = new Usage();
		if (getReceiveMailSetting() != null) {
			int index = 0;
			for (SenderAuthorization authorization: getReceiveMailSetting().getSenderAuthorizations()) {
				if (authorization.getDefaultProject().equals(projectName))
					usage.add("default project");
				if (authorization.getAuthorizedProjects() != null) {
					PatternSet patternSet = PatternSet.parse(authorization.getAuthorizedProjects());
					if (patternSet.getIncludes().contains(projectName) || patternSet.getExcludes().contains(projectName))
						usage.add("authorized projects");
				} 
				usage.prefix("sender authorization #" + index);
				index++;
			}
			usage.prefix("check incoming email");
		} 
		return usage;
	}
	
}
