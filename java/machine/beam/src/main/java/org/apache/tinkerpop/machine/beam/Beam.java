/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.machine.beam;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.Combine;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionList;
import org.apache.tinkerpop.machine.bytecode.Bytecode;
import org.apache.tinkerpop.machine.bytecode.BytecodeUtil;
import org.apache.tinkerpop.machine.coefficients.Coefficient;
import org.apache.tinkerpop.machine.coefficients.LongCoefficient;
import org.apache.tinkerpop.machine.functions.BranchFunction;
import org.apache.tinkerpop.machine.functions.CFunction;
import org.apache.tinkerpop.machine.functions.FilterFunction;
import org.apache.tinkerpop.machine.functions.FlatMapFunction;
import org.apache.tinkerpop.machine.functions.InitialFunction;
import org.apache.tinkerpop.machine.functions.MapFunction;
import org.apache.tinkerpop.machine.functions.ReduceFunction;
import org.apache.tinkerpop.machine.processor.Processor;
import org.apache.tinkerpop.machine.traversers.Traverser;
import org.apache.tinkerpop.machine.traversers.TraverserFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class Beam<C, S, E> implements Processor<C, S, E> {


    private final Pipeline pipeline;
    public static List<Traverser> OUTPUT = new ArrayList<>(); // FIX THIS!
    private final List<Fn> functions = new ArrayList<>();
    Iterator<Traverser<C, E>> iterator = null;
    private TraverserFactory<C> traverserFactory;


    public Beam(final TraverserFactory<C> traverserFactory, final List<CFunction<C>> functions) {
        this.traverserFactory = traverserFactory;
        this.pipeline = Pipeline.create();
        this.pipeline.getCoderRegistry().registerCoderForClass(Traverser.class, new TraverserCoder<>());
        PCollection<Traverser<C, ?>> collection = this.pipeline.apply(Create.of(traverserFactory.create((Coefficient) LongCoefficient.create(), 1L)));
        collection.setCoder(new TraverserCoder());
        for (final CFunction<?> function : functions) {
            collection = processFunction(collection, function, false);
        }
        collection.apply(ParDo.of(new OutputStep()));
        this.pipeline.getOptions().setRunner(new PipelineOptions.DirectRunner().create(this.pipeline.getOptions()));
    }

    private PCollection<Traverser<C, ?>> processFunction(PCollection<Traverser<C, ?>> collection, final CFunction<?> function, final boolean branching) {
        DoFn<Traverser<C, S>, Traverser<C, E>> fn = null;
        if (function instanceof BranchFunction) {
            final List<List<CFunction<C>>> branches = ((BranchFunction) function).getBranches();
            final List<PCollection<Traverser<C, ?>>> collections = new ArrayList<>(branches.size());
            for (final List<CFunction<C>> branch : branches) {
                PCollection<Traverser<C, ?>> branchCollection = collection;
                for (final CFunction<C> branchFunction : branch) {
                    branchCollection = this.processFunction(branchCollection, branchFunction, true);
                }
                collections.add(branchCollection);
            }
            collection = PCollectionList.of(collections).apply(Flatten.pCollections());
            this.functions.add(new BranchFn<>((BranchFunction<C, S, E>) function));
        } else if (function instanceof InitialFunction) {
            fn = new InitialFn((InitialFunction<C, S>) function, this.traverserFactory);
        } else if (function instanceof FilterFunction) {
            fn = new FilterFn((FilterFunction<C, S>) function);
        } else if (function instanceof FlatMapFunction) {
            fn = new FlatMapFn<>((FlatMapFunction<C, S, E>) function);
        } else if (function instanceof MapFunction) {
            fn = new MapFn<>((MapFunction<C, S, E>) function);
        } else if (function instanceof ReduceFunction) {
            final ReduceFn<C, S, E> combine = new ReduceFn<>((ReduceFunction<C, S, E>) function, this.traverserFactory);
            collection = (PCollection<Traverser<C, ?>>) collection.apply(Combine.globally((ReduceFn) combine));
            this.functions.add(combine);
        } else
            throw new RuntimeException("You need a new step type:" + function);

        if (!(function instanceof ReduceFunction) && !(function instanceof BranchFunction)) {
            if (!branching)
                this.functions.add((Fn) fn);
            collection = (PCollection<Traverser<C, ?>>) collection.apply(ParDo.of((DoFn) fn));
        }
        collection.setCoder(new TraverserCoder());
        return collection;
    }

    public Beam(final Bytecode<C> bytecode) {
        this(BytecodeUtil.getTraverserFactory(bytecode).get(), BytecodeUtil.compile(BytecodeUtil.strategize(bytecode)));
    }

    @Override
    public void addStart(final Traverser<C, S> traverser) {
        this.functions.get(0).addStart(traverser);
    }

    @Override
    public Traverser<C, E> next() {
        this.setupPipeline();
        return this.iterator.next();
    }

    @Override
    public boolean hasNext() {
        this.setupPipeline();
        return this.iterator.hasNext();
    }

    @Override
    public void reset() {

    }

    @Override
    public String toString() {
        return this.functions.toString();
    }

    private final void setupPipeline() {
        if (null == this.iterator) {
            this.pipeline.run().waitUntilFinish();
            this.iterator = (Iterator) new ArrayList<>(OUTPUT).iterator();
            OUTPUT.clear();
        }
    }

}
