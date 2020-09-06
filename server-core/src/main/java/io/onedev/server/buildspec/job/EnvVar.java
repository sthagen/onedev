package io.onedev.server.buildspec.job;

import java.io.Serializable;
import java.util.List;

import org.hibernate.validator.constraints.NotEmpty;

import io.onedev.commons.codeassist.InputSuggestion;
import io.onedev.server.util.validation.annotation.VariableName;
import io.onedev.server.web.editable.annotation.Editable;
import io.onedev.server.web.editable.annotation.Interpolative;

@Editable
public class EnvVar implements Serializable {

	private static final long serialVersionUID = 1L;

	private String name;
	
	private String value;

	@Editable(order=100, description="Specify name of the environment variable. "
			+ "<b>Note:</b> Type <tt>@</tt> to <a href='$docRoot/pages/variable-substitution.md' target='_blank' tabindex='-1'>insert variable</a>, use <tt>\\</tt> to escape normal occurrences of <tt>@</tt> or <tt>\\</tt>")
	@Interpolative(variableSuggester="suggestVariables")
	@VariableName(interpolative = true)
	@NotEmpty
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	@Editable(order=200, description="Specify value of the environment variable. "
			+ "<b>Note:</b> Type <tt>@</tt> to <a href='$docRoot/pages/variable-substitution.md' target='_blank' tabindex='-1'>insert variable</a>, use <tt>\\</tt> to escape normal occurrences of <tt>@</tt> or <tt>\\</tt>")
	@Interpolative(variableSuggester="suggestVariables")
	@NotEmpty
	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}
	
	@SuppressWarnings("unused")
	private static List<InputSuggestion> suggestVariables(String matchWith) {
		return Job.suggestVariables(matchWith);
	}

}
