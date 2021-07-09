package io.onedev.server.plugin.imports.youtrack;

import java.io.Serializable;

import org.hibernate.validator.constraints.NotEmpty;

import io.onedev.server.util.validation.annotation.ProjectName;
import io.onedev.server.web.editable.annotation.Editable;

@Editable
public class ProjectMapping implements Serializable {

	private static final long serialVersionUID = 1L;
	
	public static final String PROP_ONEDEV_PROJECT = "oneDevProject";
	
	private String youTrackProject;
	
	private String oneDevProject;
	
	@Editable(order=100, name="YouTrack Project", description="Issues will be imported from specified "
			+ "YouTrack project")
	@NotEmpty
	public String getYouTrackProject() {
		return youTrackProject;
	}

	public void setYouTrackProject(String youTrackProject) {
		this.youTrackProject = youTrackProject;
	}

	@Editable(order=200, name="OneDev Project", description="Specify OneDev project to be created as "
			+ "result of importing")
	@ProjectName
	@NotEmpty
	public String getOneDevProject() {
		return oneDevProject;
	}

	public void setOneDevProject(String oneDevProject) {
		this.oneDevProject = oneDevProject;
	}

}