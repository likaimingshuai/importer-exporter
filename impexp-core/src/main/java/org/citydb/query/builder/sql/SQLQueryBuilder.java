package org.citydb.query.builder.sql;

import java.util.Set;

import org.citydb.database.adapter.AbstractDatabaseAdapter;
import org.citydb.database.schema.mapping.FeatureType;
import org.citydb.database.schema.mapping.SchemaMapping;
import org.citydb.database.schema.path.SchemaPath;
import org.citydb.query.Query;
import org.citydb.query.builder.QueryBuildException;
import org.citydb.query.filter.FilterException;
import org.citydb.query.filter.lod.LodFilter;
import org.citydb.query.filter.lod.LodFilterMode;
import org.citydb.query.filter.selection.Predicate;
import org.citydb.query.filter.type.FeatureTypeFilter;
import org.citygml4j.model.module.citygml.CoreModule;

import org.citydb.sqlbuilder.select.Select;

public class SQLQueryBuilder {
	private final SchemaMapping schemaMapping;
	private final AbstractDatabaseAdapter databaseAdapter;
	private final String schemaName;

	private final BuildProperties buildProperties;

	public SQLQueryBuilder(SchemaMapping schemaMapping, AbstractDatabaseAdapter databaseAdapter, String schemaName, BuildProperties buildProperties) {
		this.schemaMapping = schemaMapping;
		this.databaseAdapter = databaseAdapter;
		this.schemaName = schemaName;
		this.buildProperties = buildProperties;
	}

	public SQLQueryBuilder(SchemaMapping schemaMapping, AbstractDatabaseAdapter databaseAdapter, String schemaName) {
		this(schemaMapping, databaseAdapter, schemaName, BuildProperties.defaults());
	}

	public Select buildQuery(Query query) throws QueryBuildException {
		// TODO: we need some consistency check for the Query element (possibly query.isValid())?
		Select select = null;

		// feature type filter
		FeatureTypeFilter typeFilter = query.getFeatureTypeFilter();

		if (typeFilter == null) {
			typeFilter = new FeatureTypeFilter();
			query.setFeatureTypeFilter(typeFilter);
		}

		if (typeFilter.isEmpty()) {
			try {
				typeFilter.addFeatureType(schemaMapping.getFeatureType("_CityObject", CoreModule.v2_0_0.getNamespaceURI()));
			} catch (FilterException e) {
				throw new QueryBuildException("Failed to build feature type filter.", e);
			}
		}

		// map feature types to objectclassIds
		Set<Integer> objectclassIds = new FeatureTypeFilterBuilder().buildFeatureTypeFilter(typeFilter, query.getTargetVersion());

		// selection filter
		if (query.isSetSelection()) {
			// TODO: we must check, whether the feature types announced by the feature type filter
			// are all subtypes of the root node of the schema path

			Predicate predicate = query.getSelection().getPredicate();
			PredicateBuilder predicateBuilder = new PredicateBuilder(query, objectclassIds, schemaMapping, databaseAdapter, schemaName, buildProperties);
			select = predicateBuilder.buildPredicate(predicate);	
		} else {
			FeatureType superType = schemaMapping.getCommonSuperType(typeFilter.getFeatureTypes());			
			SchemaPath schemaPath = new SchemaPath();
			schemaPath.setFirstNode(superType);

			SchemaPathBuilder builder = new SchemaPathBuilder(databaseAdapter.getSQLAdapter(), schemaName, buildProperties);
			SQLQueryContext context = builder.buildSchemaPath(schemaPath, objectclassIds, true, true);
			select = context.select;
		}

		// lod filter
		if (query.isSetLodFilter()) {
			LodFilter lodFilter = query.getLodFilter();
			if (lodFilter.getFilterMode() != LodFilterMode.OR || !lodFilter.areAllEnabled()) {
				LodFilterBuilder lodFilterBuilder = new LodFilterBuilder(schemaMapping, schemaName);
				lodFilterBuilder.buildLodFilter(query.getLodFilter(), typeFilter, query.getTargetVersion(), select);
			}
		}
		
		// set distinct on select if required
		if (buildProperties.isUseDistinct())
			select.setDistinct(true);

		return select;
	}

	public SQLQueryContext buildSchemaPath(SchemaPath schemaPath, boolean addProjection) throws QueryBuildException {
		SchemaPathBuilder builder = new SchemaPathBuilder(databaseAdapter.getSQLAdapter(), schemaName, buildProperties);
		FeatureTypeFilter typeFilter = new FeatureTypeFilter(false);

		try {
			typeFilter.addFeatureType(schemaPath.getFirstNode().getPathElement());
		} catch (FilterException e) {
			throw new QueryBuildException("Failed to build feature type filter.", e);
		}

		// map feature types to objectclassIds
		Set<Integer> objectclassIds = new FeatureTypeFilterBuilder().buildFeatureTypeFilter(typeFilter);

		return builder.buildSchemaPath(schemaPath, objectclassIds, addProjection, true);
	}

	public BuildProperties getBuildProperties() {
		return buildProperties;
	}

}