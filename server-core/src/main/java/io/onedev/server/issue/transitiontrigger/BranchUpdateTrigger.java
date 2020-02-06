package io.onedev.server.issue.transitiontrigger;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.validator.constraints.NotEmpty;

import io.onedev.commons.codeassist.InputSuggestion;
import io.onedev.server.model.Project;
import io.onedev.server.search.entity.issue.IssueQueryLexer;
import io.onedev.server.web.editable.annotation.Editable;
import io.onedev.server.web.editable.annotation.IssueQuery;
import io.onedev.server.web.editable.annotation.NameOfEmptyValue;
import io.onedev.server.web.editable.annotation.Patterns;
import io.onedev.server.web.util.SuggestionUtils;

@Editable(order=500, name="Code is committed")
public class BranchUpdateTrigger extends TransitionTrigger {

	private static final long serialVersionUID = 1L;

	private String branches;

	public BranchUpdateTrigger() {
		setIssueQuery(io.onedev.server.search.entity.issue.IssueQuery
				.getRuleName(IssueQueryLexer.FixedInCurrentCommit));		
	}
	
	@Editable(order=200, name="Applicable Branches", description="Optionally specify space-separated branches "
			+ "applicable for this trigger. Use * or ? for wildcard match")
	@Patterns(suggester = "suggestBranches")
	@NameOfEmptyValue("Any branch")
	public String getBranches() {
		return branches;
	}

	public void setBranches(String branches) {
		this.branches = branches;
	}
	
	@SuppressWarnings("unused")
	private static List<InputSuggestion> suggestBranches(String matchWith) {
		Project project = Project.get();
		if (project != null)
			return SuggestionUtils.suggestBranches(project, matchWith);
		else
			return new ArrayList<>();
	}

	@Editable(order=1000, name="Applicable Issues", description="Specify criteria of issues applicable for this transition")
	@IssueQuery(withOrder = false, withCurrentUserCriteria = false, withCurrentBuildCriteria = false, 
			withCurrentPullRequestCriteria = false, withCurrentCommitCriteria = true)
	@NotEmpty
	@Override
	public String getIssueQuery() {
		return super.getIssueQuery();
	}

	public void setIssueQuery(String issueQuery) {
		super.setIssueQuery(issueQuery);
	}
	
	@Override
	public String getDescription() {
		if (branches != null)
			return "Committed to branches '" + branches + "'";
		else
			return "Committed to any branch";
	}
	
}
