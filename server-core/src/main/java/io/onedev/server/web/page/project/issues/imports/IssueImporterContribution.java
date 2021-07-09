package io.onedev.server.web.page.project.issues.imports;

import java.io.Serializable;
import java.util.Collection;

import io.onedev.commons.launcher.loader.ExtensionPoint;

@ExtensionPoint
public interface IssueImporterContribution {

	Collection<IssueImporter<? extends Serializable, ? extends Serializable>> getImporters();
	
}
