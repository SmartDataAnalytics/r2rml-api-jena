package org.aksw.r2rml.jena.arq.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.aksw.jena_sparql_api.utils.NodeUtils;
import org.aksw.r2rml.jena.domain.api.GraphMap;
import org.aksw.r2rml.jena.domain.api.ObjectMapType;
import org.aksw.r2rml.jena.domain.api.PredicateMap;
import org.aksw.r2rml.jena.domain.api.PredicateObjectMap;
import org.aksw.r2rml.jena.domain.api.SubjectMap;
import org.aksw.r2rml.jena.domain.api.TermMap;
import org.aksw.r2rml.jena.domain.api.TriplesMap;
import org.aksw.r2rml.jena.vocab.RR;
import org.apache.jena.ext.com.google.common.collect.BiMap;
import org.apache.jena.ext.com.google.common.collect.HashBiMap;
import org.apache.jena.ext.com.google.common.collect.Sets;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.shacl.lib.ShLib;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.core.VarAlloc;
import org.apache.jena.sparql.expr.E_IsBlank;
import org.apache.jena.sparql.expr.E_StrDatatype;
import org.apache.jena.sparql.expr.E_StrLang;
import org.apache.jena.sparql.expr.E_URI;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprVar;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.modify.request.QuadAcc;
import org.apache.jena.sparql.syntax.Template;
import org.apache.jena.vocabulary.XSD;

public class R2rmlImporter {

	/**
	 * Expands rr:subject, rr:predicate, rr:object and rr:graph to term maps
	 * in order to allow for uniform processing
	 * 
	 * @param tm The triple map for which to expant all mentioned short cuts
	 */
	public static void expandShortcuts(TriplesMap tm) {

		// Implementation note: The wrapping with new ArrayList<>(...) is needed
		// because all objects are backed by the same graph which in general
		// does not allow for concurrent modification.
		// I.e. In general it is not possible to have an iterator open on a graph
		// and have another operation modify the same graph (even if the involved triples
		// are completely unrelated)
		
		// 
		// rr:subject
		Resource s = tm.getSubject();
		if (s != null) {
			tm.getOrSetSubjectMap().setConstant(s);
			tm.setSubject(null);
		}
		
		
		// rr:graph on subject map
		SubjectMap sm = tm.getSubjectMap();		
		Set<Resource> smgs = sm.getGraphs();
		for (Resource smg : new ArrayList<>(smgs)) {
			sm.addNewGraphMap().setConstant(smg);
		}
		smgs.clear();

		
		Set<PredicateObjectMap> poms = tm.getPredicateObjectMaps();
		for (PredicateObjectMap pom : new ArrayList<>(poms)) {

			// rr:graph on predicate-object map
			Set<Resource> gs = pom.getGraphs();
			for (Resource g : new ArrayList<>(gs)) {
				pom.addNewGraphMap().setConstant(g);
			}
			gs.clear();

			Set<Resource> ps = pom.getPredicates();
			for (Resource p : new ArrayList<>(ps)) {
				pom.addNewPredicateMap().setConstant(p);
			}			
			ps.clear();

			Set<Resource> os = pom.getObjects();
			for (Resource o : new ArrayList<>(os)) {
				pom.addNewObjectMap().setConstant(o);
			}
			os.clear();
		}
	}
	
	public static void validateR2rml(Model dataModel) {
		Model shaclModel = RDFDataMgr.loadModel("r2rml.core.shacl.ttl");

		// Perform the validation of everything, using the data model
		// also as the shapes model - you may have them separated
//		Resource result = ValidationUtil.validateModel(dataModel, shaclModel, true);		

	    ValidationReport report = ShaclValidator.get().validate(shaclModel.getGraph(), dataModel.getGraph());
//	    ShLib.printReport(report);
//	    System.out.println();
//	    RDFDataMgr.write(System.out, report.getModel(), Lang.TTL);

	    boolean conforms = report.conforms();
//		boolean conforms = result.getProperty(SH.conforms).getBoolean();
		
		if(!conforms) {
			// Print violations
		    ShLib.printReport(report);
			RDFDataMgr.write(System.err, report.getModel(), RDFFormat.TURTLE_PRETTY);
			throw new RuntimeException("Shacl validation failed; see report above");
		}
	}
	
	// It makes sense to have the validation method part of the object - instead of just using a static method
	public void validate(Model dataModel) {
		validateR2rml(dataModel);
	}

	public Collection<TriplesMapToSparqlMapping> read(Model rawModel) {
		Model model = ModelFactory.createDefaultModel();
		model.add(rawModel);
		
		
		List<TriplesMap> triplesMaps = model.listSubjectsWithProperty(RR.logicalTable).mapWith(r -> r.as(TriplesMap.class)).toList();

//		for(TriplesMap tm : triplesMaps) {
			// TODO Integrate validation with shacl, as this gives us free reports of violations
//		}
		
		List<TriplesMapToSparqlMapping> result = triplesMaps.stream()
				.map(tm -> read(tm))
				.collect(Collectors.toList());
	
		return result;
	}
	

	/**
	 * Allocates a variable for a given term map and maps it to the term map's corresponding
	 * SPARQL expression.
	 * If a variable maps to a constant expression then no entry is made to nodeToExpr.
	 * 
	 * @param tm
	 * @param nodeToExpr
	 * @param varGen
	 * @return
	 */
	public Node allocateVar(TermMap tm, BiMap<Var, Expr> nodeToExpr, VarAlloc varGen) {
		Node result;
		BiMap<Expr, Var> exprToNode = nodeToExpr.inverse();
		
		Expr expr = termMapToExpr(tm);
		result = exprToNode.get(expr);
		
		if(result == null) {
			// If the expr is a constant, just use its node as the result; no need to track this in the map
			if(expr.isConstant()) {
				result = expr.getConstant().asNode();
			} else {				
				// Allocate a new variable
				Var v = varGen.allocVar();
				nodeToExpr.put(v, expr);
				result = v;
			}
		}
		
		return result;
	}

	

	/**
	 * Construct triples by creating the cartesian product between g, s, p, and o term maps
	 *
	 * https://www.w3.org/TR/r2rml/#generated-triples
	 * 
	 * Note on graphs: the spec states: "If sgm and pogm are empty: rr:defaultGraph; otherwise:
	 * union of subject_graphs and predicate-object_graphs"
	 * 
	 * @param tm
	 * @return
	 */
	public TriplesMapToSparqlMapping read(TriplesMap tm) {
		
		expandShortcuts(tm);

		SubjectMap sm = tm.getSubjectMap();
		Objects.requireNonNull(sm, "SubjectMap was null on " + tm);
		
		Set<GraphMap> sgms = sm.getGraphMaps();
		
		// Mapping of expressions to allocated variables
		BiMap<Var, Expr> varToExpr = HashBiMap.create();
		Map<TermMap, Var> termMapToVar = new HashMap<>();

		// Accumulator for generated quads
		QuadAcc quadAcc = new QuadAcc();

		// TODO Allow customization of variable allocation
		VarAlloc varGen = new VarAlloc("v");

		for(PredicateObjectMap pom : tm.getPredicateObjectMaps()) {
			Set<GraphMap> pogms = pom.getGraphMaps();
			
			// egms = effective graph maps
			Set<GraphMap> egms = Sets.union(sgms, pogms);
						
			if(egms.isEmpty()) {
				egms = Collections.singleton(null);
			}

			Set<PredicateMap> pms = pom.getPredicateMaps();
			Set<ObjectMapType> oms = pom.getObjectMaps();

			for(GraphMap gm : egms) {			
				for(PredicateMap pm : pms) {
					for(ObjectMapType om : oms) {
						Node g = gm == null ? RR.defaultGraph.asNode() : allocateVar(gm, varToExpr, varGen);
						Node s = allocateVar(sm, varToExpr, varGen);
						Node p = allocateVar(pm, varToExpr, varGen);
						Node o = allocateVar(om.asTermMap(), varToExpr, varGen);
						
						// TODO Add booking of var to term-map mapping for debugging purposes
						
						if (g.equals(RR.defaultGraph.asNode())) {
							Triple triple = new Triple(s, p, o);
							quadAcc.addTriple(triple);
						} else {
							Quad quad = new Quad(g, s, p, o);
							quadAcc.addQuad(quad);
						}
					}
				}
			}
		}
		
		Template template = new Template(quadAcc);
		TriplesMapToSparqlMapping result = new TriplesMapToSparqlMapping(
			tm, template, termMapToVar, varToExpr);
		
		return result;
	}

	/**
	 * Convert a term map to a corresponing SPARQL expression
	 * 
	 * @param tm
	 * @return
	 */
	public static Expr termMapToExpr(TermMap tm) {
		Expr result;
		
		String template;
		RDFNode constant;
		if((template = tm.getTemplate()) != null) {
			Expr arg = R2rmlTemplateParser.parseTemplate(template);
			
			// TODO Support rr:termType rr:BlankNode
			result = new E_URI(arg);
			
		} else if((constant = tm.getConstant()) != null) {
			result = NodeValue.makeNode(constant.asNode());
		} else {
			String colName;
			if((colName = tm.getColumn()) != null) {
			
				ExprVar column = new ExprVar(colName);
				Resource dtype = tm.getDatatype();
				if(dtype != null || XSD.xstring.equals(dtype)) {
					Node dn = dtype.asNode();
					if (!dn.isURI()) {
						throw new RuntimeException("Datatype " + dtype + " is not an IRI");
					}
					String du = dn.getURI();
					
					result = du.equals(NodeUtils.R2RML_IRI)
								? new E_URI(column)
								: du.equals(NodeUtils.R2RML_BlankNode)
									? new E_IsBlank(column)
									: new E_StrDatatype(column, NodeValue.makeNode(dn));

				} else {
					String language = Optional.ofNullable(tm.getLanguage()).map(String::trim).orElse("");
					result = language.isEmpty()
							? new E_StrDatatype(column, NodeValue.makeNode(XSD.xstring.asNode()))
							: new E_StrLang(column, NodeValue.makeString(language));
				}
			} else {
				throw new RuntimeException("TermMap does neither define rr:template, rr:constant nor rr:column " + tm);
			}

		}
		
		return result;
	}
	
// TODO Add util function to derive a name for the TriplesMapToSparqlMapping?
//	String name = Optional.ofNullable(tm.getProperty(RDFS.label))
//			.map(Statement::getString)
//			.orElseGet(() -> tm.isURIResource() ? tm.getURI() : "" + tm);

}
