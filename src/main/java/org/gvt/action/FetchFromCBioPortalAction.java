package org.gvt.action;

import org.biopax.paxtools.causality.data.CBioPortalAccessor;
import org.biopax.paxtools.causality.data.CancerStudy;
import org.biopax.paxtools.causality.data.CaseList;
import org.biopax.paxtools.causality.data.GeneticProfile;
import org.biopax.paxtools.causality.model.Alteration;
import org.biopax.paxtools.causality.model.AlterationPack;
import org.biopax.paxtools.causality.model.Change;
import org.biopax.paxtools.model.Model;
import org.biopax.paxtools.model.level3.RelationshipXref;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.MessageBox;
import org.gvt.ChisioMain;
import org.gvt.gui.FetchFromCBioPortalDialog;
import org.gvt.util.HGNCUtil;
import org.patika.mada.dataXML.*;
import org.patika.mada.gui.ExperimentDataConvertionWizard;
import org.patika.mada.gui.FetchFromGEODialog;

import javax.swing.*;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.MarshalException;
import javax.xml.bind.Marshaller;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FetchFromCBioPortalAction extends Action {
    ChisioMain main;

   	public FetchFromCBioPortalAction (ChisioMain main) {
   		super("Fetch from cBio Portal...");
   		this.main = main;
   	}

    public void run() {
        // First things first
        if(main.getRootGraph() == null) {
             MessageDialog.openError(main.getShell(), "Error!",
                     "No BioPAX model loaded.");
             return;
        }

        FetchFromCBioPortalDialog dialog = new FetchFromCBioPortalDialog(main);
        dialog.open();

        CBioPortalAccessor cBioPortalAccessor = dialog.getAccessor();

        // If user clicks on the 'Load' button, this list should not be empty
        // Otherwise, just quit
        List<GeneticProfile> currentGeneticProfiles = cBioPortalAccessor.getCurrentGeneticProfiles();
        if(currentGeneticProfiles.isEmpty()) {
            return;
        }

        // Extract gene names from the current BioPAX model
        java.util.List<String> geneNames = new ArrayList<String>();
        Model model = main.getOwlModel();
        for (RelationshipXref xref : model.getObjects(RelationshipXref.class)) {
            if (xref.getDb().startsWith("HGNC")) {
                String[] tokens = xref.getId().split(":");
                // Is ist HGNC:GENE or HGNC:HGNC:123123
                String geneName =
                        (tokens.length > 1)
                                ? HGNCUtil.getSymbol(Integer.parseInt(tokens[1].trim()))
                                : tokens[0].trim();

                geneNames.add(geneName);
            }
        }

        // We don't want to merge all data types into a single result
        // so let's extract'em and work one by one
        ArrayList<GeneticProfile> geneticProfiles
                = new ArrayList<GeneticProfile>(currentGeneticProfiles);
        currentGeneticProfiles.clear();

        ObjectFactory expFactory = new ObjectFactory();

        CancerStudy cancerStudy = cBioPortalAccessor.getCurrentCancerStudy();
        CaseList caseList = cBioPortalAccessor.getCurrentCaseList();

        // Now load data
        for (GeneticProfile geneticProfile : geneticProfiles) {
            main.lockWithMessage("Loading " + geneticProfile.getName() + "...");
            cBioPortalAccessor.setCurrentGeneticProfiles(Collections.singletonList(geneticProfile));

            ChisioExperimentData experimentData;
            try {
                experimentData = expFactory.createRootExperimentData();
            } catch (JAXBException e) {
                MessageDialog.openError(main.getShell(), "Error!",
                        "Could not create experiment.");
                return;
            }
            experimentData.setExperimentType(geneticProfile.getType().toString());
            String experimentInfo = cancerStudy.getName() + " | "
                    + caseList.getDescription() + " (" + caseList.getCases().length + " cases) | "
                    + geneticProfile.getName() + " | "
                    + geneticProfile.getDescription();
            experimentData.setExperimentSetInfo(experimentInfo);

            Alteration alterationType =
                    GeneticProfile.GENETIC_PROFILE_TYPE.convertToAlteration(geneticProfile.getType());

            int count = 0;
             // Create sub-experiments for each sample
             for (String caseId : caseList.getCases()) {
                 try {
                     Experiment experiment = expFactory.createExperiment();
                     experiment.setNo(count++);
                     experiment.setExperimentName(caseId);

                     experimentData.getExperiment().add(experiment);
                 } catch (JAXBException e) {
                     MessageDialog.openError(main.getShell(), "Error!",
                             "Could not create experiment.");
                     return;
                 }
             }

            // Iterate over genes
            // TODO: optimize this and grab all results with single request.
            for (String gene : geneNames) {
                AlterationPack alterations = cBioPortalAccessor.getAlterations(gene);
                try {
                    Row row = expFactory.createRow();
                    Reference ref = expFactory.createReference();
                    ref.setDb(ExperimentDataConvertionWizard.COMMON_GENE_SYMBOL_COLUMN_NAMES.iterator().next());
                    ref.setValue(gene);
                    row.getRef().add(ref);

                    count = 0;
                    for (Change change : alterations.get(alterationType)) {
                        double expValue = .0D;

                        if(change.isAbsent() || !change.isAltered()) {
                            expValue = alterationType.isGenomic() ? .0D : 1.0D;
                        } else {
                            switch (change) {
                                case ACTIVATING:
                                    expValue = 1.0D;
                                    break;
                                case INHIBITING:
                                    expValue = -1.0D;
                                    break;
                            }
                        }

                        ValueTuple tuple = expFactory.createValueTuple();
                        tuple.setNo(count++);
                        tuple.setValue(expValue);
                        row.getValue().add(tuple);
                    }
                } catch (JAXBException e) {
                    MessageDialog.openError(main.getShell(), "Error!",
                            "Could not process experiment.");
                    return;
                }
            }

            String fileName
                    = saveExperiment(experimentData, geneticProfile.getId() + "_" + caseList.getId() + ".ced");

            if(fileName != null)
                (new LoadExperimentDataAction(main, fileName)).run();

            main.unlock();
        }
    }

    public String saveExperiment(ChisioExperimentData data, String fileNameSuggestion) {
        String fileName = null;
  		boolean done = false;

  		while (!done)
  		{
  			FileDialog fileChooser = new FileDialog(main.getShell(), SWT.SAVE);

  			if (fileNameSuggestion != null)
  			{
  				if (!fileNameSuggestion.endsWith(".ced"))
  				{
  					if (fileNameSuggestion.indexOf(".") > 0)
  					{
  						fileNameSuggestion = fileNameSuggestion.substring(
  							0, fileNameSuggestion.lastIndexOf("."));
  					}
  					fileNameSuggestion += ".ced";
  				}

  				fileChooser.setFileName(fileNameSuggestion);
  			}

  			String[] filterExtensions = new String[]{"*.ced"};
  			String[] filterNames = new String[]{"BioPAX (*.ced)"};

  			fileChooser.setFilterExtensions(filterExtensions);
  			fileChooser.setFilterNames(filterNames);
  			fileName = fileChooser.open();

  			if (fileName == null)
  			{
  				// User has cancelled, so quit and return
  				done = true;
  			}
  			else
  			{
  				// User has selected a file; see if it already exists
  				File file = new File(fileName);

  				if (file.exists()) {
  					// The file already exists; asks for confirmation
  					MessageBox mb = new MessageBox(
  						fileChooser.getParent(),
  						SWT.ICON_WARNING | SWT.YES | SWT.NO);

  					// We really should read this string from a
  					// resource bundle
  					mb.setMessage(fileName +
  						" already exists. Do you want to overwrite?");
  					mb.setText("Confirm Replace File");
  					// If they click Yes, we're done and we drop out. If
  					// they click No, we redisplay the File Dialog
  					done = mb.open() == SWT.YES;
  				}
  				else {
  					// File does not exist, so drop out
  					done = true;
  				}
  			}
  		}

        if(fileName == null)
            return fileName;

        try {
            JAXBContext jc = JAXBContext.newInstance("org.patika.mada.dataXML");
            Marshaller m = jc.createMarshaller();
            BufferedWriter writer = new BufferedWriter(new FileWriter(fileName));

            m.setProperty("jaxb.formatted.output", Boolean.TRUE);
            m.marshal(data, writer);

            writer.close();
        } catch (Exception e) {
            MessageDialog.openError(main.getShell(), "Error!",
                     "Could not create experiment.");
            return null;
        }

        return fileName;
    }
}