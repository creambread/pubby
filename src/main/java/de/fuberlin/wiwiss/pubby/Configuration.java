package de.fuberlin.wiwiss.pubby;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.StmtIterator;
import com.hp.hpl.jena.shared.PrefixMapping;
import com.hp.hpl.jena.shared.impl.PrefixMappingImpl;
import com.hp.hpl.jena.sparql.vocabulary.FOAF;
import com.hp.hpl.jena.util.FileManager;
import com.hp.hpl.jena.vocabulary.DC;
import com.hp.hpl.jena.vocabulary.RDF;
import com.hp.hpl.jena.vocabulary.RDFS;
import com.hp.hpl.jena.vocabulary.XSD;

import de.fuberlin.wiwiss.pubby.sources.DataSource;
import de.fuberlin.wiwiss.pubby.sources.IndexDataSource;
import de.fuberlin.wiwiss.pubby.sources.MergeDataSource;
import de.fuberlin.wiwiss.pubby.vocab.CONF;

/**
 * The server's configuration.
 * 
 * @author Richard Cyganiak (richard@cyganiak.de)
 * @version $Id$
 */
public class Configuration extends ResourceReader {
	
	public static Configuration create(Model model) {
		StmtIterator it = model.listStatements(null, RDF.type, CONF.Configuration);
		if (!it.hasNext()) {
			throw new IllegalArgumentException(
					"No resource with type conf:Configuration found in configuration file");
		}
		return new Configuration(it.nextStatement().getSubject());
	}
	
	private final Model modelWithImports;
	private final PrefixMapping prefixes;
	private final String webBase;
	private final Collection<Property> labelProperties;
	private final Collection<Property> commentProperties;
	private final Collection<Property> imageProperties;
	private final ArrayList<Dataset> datasets = new ArrayList<Dataset>();
	private final VocabularyStore vocabularyStore;
	private final DataSource dataSource;
	private final String indexIRI;
	
	public Configuration(Resource configuration) {
		super(configuration);
		webBase = getRequiredIRI(CONF.webBase);

		modelWithImports = ModelFactory.createDefaultModel().add(getModel());
		for (String sourceURL: getIRIs(CONF.loadVocabularyFromURL)) {
			FileManager.get().readModel(modelWithImports, sourceURL);
		}
		vocabularyStore = new VocabularyStore(modelWithImports, this);

		for (Resource r: getResources(CONF.dataset)) {
			datasets.add(new Dataset(r, this));
		}
		
		labelProperties = getProperties(CONF.labelProperty);
		if (labelProperties.isEmpty()) {
			labelProperties.add(RDFS.label);
			labelProperties.add(DC.title);
			labelProperties.add(FOAF.name);
		}
		commentProperties = getProperties(CONF.commentProperty);
		if (commentProperties.isEmpty()) {
			commentProperties.add(RDFS.comment);
			commentProperties.add(DC.description);
		}
		imageProperties = getProperties(CONF.imageProperty);
		if (imageProperties.isEmpty()) {
			imageProperties.add(FOAF.depiction);
		}

		prefixes = new PrefixMappingImpl();
		if (hasProperty(CONF.usePrefixesFrom)) {
			for (String iri: getIRIs(CONF.usePrefixesFrom)) {
				prefixes.setNsPrefixes(FileManager.get().loadModel(iri));
			}
		} else {
			prefixes.setNsPrefixes(getModel());
		}
		if (prefixes.getNsURIPrefix(CONF.NS) != null) {
			prefixes.removeNsPrefix(prefixes.getNsURIPrefix(CONF.NS));
		}
		// If no prefix is defined for the RDF and XSD namespaces, set them,
		// unless that would overwrite something. This is the namespaces that
		// have syntactic sugar in Turtle.
		ModelUtil.addNSIfUndefined(prefixes, "rdf", RDF.getURI());
		ModelUtil.addNSIfUndefined(prefixes, "xsd", XSD.getURI());
		dataSource = buildDataSource();

		// Sanity check to spot typical configuration problem
		if (dataSource.getIndex().isEmpty()) {
			throw new ConfigurationException("The index is empty. " + 
					"Try adding conf:datasetBase to your datasets, " + 
					"check any conf:datasetURIPatterns, " + 
					"and check that all data sources actually contain data.");
		}
		String resourceBase = getWebApplicationBaseURI() + getWebResourcePrefix();
		if (hasProperty(CONF.indexResource)) {
			indexIRI = getIRI(CONF.indexResource);
			// Sanity check to spot typical configuration problem
			if (dataSource.describeResource(indexIRI).isEmpty()) {
				throw new ConfigurationException(
						"conf:indexResource <" + indexIRI + 
						"> not found in data sets. " + 
						"Try disabling the conf:indexResource to get " +
						"a list of found resources.");
			}
		} else {
			indexIRI = resourceBase;
		}
	}

	private DataSource buildDataSource() {
		List<DataSource> sources = new ArrayList<DataSource>(datasets.size());
		for (Dataset dataset: datasets) {
			sources.add(dataset.getDataSource());
		}
		DataSource result = new MergeDataSource(sources, prefixes);
		// If we don't have an indexResource, and there is no resource
		// at the home URL in any of the datasets, then add an
		// index builder. It will be responsible for handling the
		// homepage/index resource.
		// TODO: Shouldn't we make the index data source available even if there
		//       is an indexResource?
		String indexIRI = webBase + getWebResourcePrefix();
		if (!hasProperty(CONF.indexResource) && 
				result.describeResource(indexIRI).isEmpty()) {
			result = new IndexDataSource(indexIRI, result);
		}
		return result;
	}

	/**
	 * A composite {@link DataSource} representing the merge of all datasets.
	 */
	public DataSource getDataSource() {
		return dataSource;
	}

	/**
	 * The <code>conf:dataset</code> blocks.
	 */
	public List<Dataset> getDatasets() {
		return datasets;
	}
	
	/**
	 * @param relativeRequestURI URI relative to the Pubby root (<code>conf:webBase</code>)
	 * @param isRelativeToPubbyRoot If true, the IRI is relative to the Pubby
	 *        root (<code>conf:webBase</code>); otherwise, the IRI is relative
	 *        to some non-resource namespace such as <code>/page/</code>. The
	 *        distinction matters if <code>conf:webResourcePrefix</code> is set.
	 */
	public HypermediaControls getControls(String relativeRequestURI, 
			boolean isRelativeToPubbyRoot) {
		String relativeIRI = IRIEncoder.toIRI(relativeRequestURI);
		if (isRelativeToPubbyRoot) {
			if (!relativeIRI.startsWith(getWebResourcePrefix())) return null;
		} else {
			relativeIRI = relativeIRI.substring(getWebResourcePrefix().length());
		}
		return HypermediaControls.createFromPubbyPath(relativeIRI, this);
	}
	
	public PrefixMapping getPrefixes() {
		return prefixes;
	}

	public Collection<Property> getLabelProperties() {
		return labelProperties;
	}
	
	public Collection<Property> getCommentProperties() {
		return commentProperties;
	}
	
	public Collection<Property> getImageProperties() {
		return imageProperties;
	}
	
	public String getDefaultLanguage() {
		return getString(CONF.defaultLanguage);
	}
	
	/**
	 * The "home" resource. If its IRI is not the web server base, then the
	 * web server will redirect there.
	 */
	public String getIndexIRI() {
		return indexIRI;
	}
	
	public String getProjectLink() {
		return getIRI(CONF.projectHomepage);
	}

	public String getProjectName() {
		return getString(CONF.projectName);
	}

	public String getWebApplicationBaseURI() {
		return webBase;
	}

	public String getWebResourcePrefix() {
		return getString(CONF.webResourcePrefix, "");
	}

	public boolean showLabels() {
		return getBoolean(CONF.showLabels, true);
	}

	public VocabularyStore getVocabularyStore() {
		return vocabularyStore;
	}
	
	public Collection<Property> getHighOutdegreeProperties() {
		if (highOutdegreePropertyCache == null) {
			highOutdegreePropertyCache = getPropertiesByType(CONF.HighOutdregreeProperty);
		}
		return highOutdegreePropertyCache;
	}
	private Collection<Property> highOutdegreePropertyCache = null;

	public Collection<Property> getHighIndegreeProperties() {
		if (highIndegreePropertyCache == null) {
			highIndegreePropertyCache = getPropertiesByType(CONF.HighIndregreeProperty);
		}
		return highIndegreePropertyCache;
	}
	private Collection<Property> highIndegreePropertyCache = null;

	/**
	 * Gets all values of <tt>conf:browsableNamespace</tt> declared on the
	 * configuration resource. Does not include values declared on specific
	 * datasets.
	 *  
	 * @return Namespace IRIs of browsable namespaces
	 */
	public Set<String> getBrowsableNamespaces() {
		return getIRIs(CONF.browsableNamespace);
	}
	
	private Collection<Property> getPropertiesByType(Resource type) {
		Collection<Property> results = new ArrayList<Property>();
		StmtIterator it = modelWithImports.listStatements(null, RDF.type, type);
		while (it.hasNext()) {
			results.add(it.next().getSubject().as(Property.class));
		}
		return results;
	}
}
