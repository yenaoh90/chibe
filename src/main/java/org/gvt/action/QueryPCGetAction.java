package org.gvt.action;

import cpath.client.util.CPathException;
import org.biopax.paxtools.model.BioPAXLevel;
import org.biopax.paxtools.model.Model;
import org.eclipse.jface.dialogs.MessageDialog;
import org.gvt.ChisioMain;
import org.gvt.gui.AbstractQueryParamDialog;
import org.gvt.gui.StringInputDialog;
import org.gvt.model.basicsif.BasicSIFGraph;
import org.patika.mada.graph.GraphObject;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * @author Ozgun Babur
 *
 */
public class QueryPCGetAction extends QueryPCAction
{
	protected Set<String> ids;
	private String lastEntry;

	public QueryPCGetAction(ChisioMain main, boolean useSelected)
	{
		super(main, "Object With Database ID ...", useSelected, false);
	}

	public void run()
	{
        if(main.getBioPAXModel() == null || main.getBioPAXModel().getLevel().equals(BioPAXLevel.L3))
        {
            if (!useSelected)
            {
				if (this.ids == null || this.ids.isEmpty())
				{
					String idStr = "";

					if (lastEntry != null) idStr = lastEntry;

					StringInputDialog dialog = new StringInputDialog(main.getShell(), "Get Objects",
						"Enter Pathway Commons ID of database object", idStr,
						"Find the specified object");

					idStr = dialog.open();
					lastEntry = idStr;

					if (this.ids == null) this.ids = new HashSet<String>();
					Collections.addAll(this.ids, idStr.split("\\s+"));
					if (this.ids.isEmpty()) this.ids = null;
				}
            }

            execute();
            ids = null;
        }
        else
        {
            MessageDialog.openError(main.getShell(), "Incompatible Levels",
				"This query is only applicable to Level 3 models.");
        }
    }

	public void setIDs(Set<String> ids)
	{
		this.ids = ids;
	}

	@Override
	protected Model doQuery() throws CPathException
	{
		if (useSelected && !options.getSourceList().isEmpty())
		{
			ids = new HashSet<String>(options.getSourceList());
		}

		return getPCGetQuery().sources(ids).result();
	}

	@Override
	protected Collection<GraphObject> doSIFQuery(BasicSIFGraph graph) throws CPathException
	{
		throw new UnsupportedOperationException("Cannot get from SIF");
	}

	@Override
	protected AbstractQueryParamDialog getDialog()
	{
		return null;
	}

	@Override
	protected boolean canQuery()
	{
		return !(ids == null &&
			(options.getSourceList() == null || options.getSourceList().isEmpty()));
	}
}
