/* Soot - a J*va Optimization Framework
 * Copyright (C) 2002 Ondrej Lhotak
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 * Boston, MA 02111-1307, USA.
 */

package soot.jimple.spark.builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;

import soot.G;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.jimple.spark.geom.geomPA.GeomPointsTo;
import soot.jimple.spark.internal.SparkNativeHelper;
import soot.jimple.spark.pag.MethodPAG;
import soot.jimple.spark.pag.PAG;
import soot.jimple.spark.solver.OnFlyCallGraph;
import soot.jimple.toolkits.callgraph.CallGraphBuilder;
import soot.jimple.toolkits.callgraph.Edge;
import soot.jimple.toolkits.callgraph.ReachableMethods;
import soot.jimple.toolkits.pointer.DumbPointerAnalysis;
import soot.jimple.toolkits.pointer.util.NativeMethodDriver;
import soot.options.SparkOptions;
import soot.util.queue.QueueReader;

/**
 * A context insensitive pointer assignment graph builder.
 * 
 * @author Ondrej Lhotak
 */
public class ContextInsensitiveBuilder {
    private static final Logger logger = LoggerFactory.getLogger(ContextInsensitiveBuilder.class);
	public void preJimplify() {
		boolean change = true;
		while (change) {
			change = false;
			for (Iterator<SootClass> cIt = new ArrayList<SootClass>(Scene.v().getClasses()).iterator(); cIt
					.hasNext();) {
				final SootClass c = cIt.next();
				for (final SootMethod m : c.getMethods()) {
					if (!m.isConcrete())
						continue;
					if (m.isNative())
						continue;
					if (m.isPhantom())
						continue;
					if (!m.hasActiveBody()) {
						change = true;
						m.retrieveActiveBody();
					}
				}
			}
		}
	}

	/** Creates an empty pointer assignment graph. */
	public PAG setup(SparkOptions opts) {
		pag = opts.geom_pta() ? new GeomPointsTo(opts) : new PAG(opts);
		if (opts.simulate_natives()) {
			pag.nativeMethodDriver = new NativeMethodDriver(new SparkNativeHelper(pag));
		}
		if (opts.on_fly_cg() && !opts.vta()) {
			ofcg = new OnFlyCallGraph(pag, opts.apponly());
			pag.setOnFlyCallGraph(ofcg);
		} else {
			cgb = new CallGraphBuilder(DumbPointerAnalysis.v());
		}
		return pag;
	}

	/** Fills in the pointer assignment graph returned by setup. */
	public void build() {
		QueueReader<Edge> callEdges;
		if (ofcg != null) {
			callEdges = ofcg.callGraph().listener();
			ofcg.build();
			reachables = ofcg.reachableMethods();
			reachables.update();
		} else {
			callEdges = cgb.getCallGraph().listener();
			cgb.build();
			reachables = cgb.reachables();
		}
		for (final SootClass c : Scene.v().getClasses()) {
			handleClass(c);
		}
		while (callEdges.hasNext()) {
			Edge e = callEdges.next();
			if (e.getTgt().method().getDeclaringClass().isConcrete()) {
				if (e.tgt().isConcrete() || e.tgt().isNative())
					MethodPAG.v(pag, e.tgt()).addToPAG(null);
				pag.addCallTarget(e);
			}
		}

		if (pag.getOpts().verbose()) {
			logger.debug("Total methods: " + totalMethods);
			logger.debug("Initially reachable methods: " + analyzedMethods);
			logger.debug("Classes with at least one reachable method: " + classes);
		}
	}

	/* End of public methods. */
	/* End of package methods. */
	protected void handleClass(SootClass c) {
		boolean incedClasses = false;
		if (c.isConcrete())
			for (SootMethod m : c.getMethods()) {
				if (!m.isConcrete() && !m.isNative())
					continue;
				totalMethods++;
				if (reachables.contains(m)) {
					MethodPAG mpag = MethodPAG.v(pag, m);
					mpag.build();
					mpag.addToPAG(null);
					analyzedMethods++;
					if (!incedClasses) {
						incedClasses = true;
						classes++;
					}
				}
			}
	}

	protected PAG pag;
	protected CallGraphBuilder cgb;
	protected OnFlyCallGraph ofcg;
	protected ReachableMethods reachables;
	int classes = 0;
	int totalMethods = 0;
	int analyzedMethods = 0;
	int stmts = 0;
}
