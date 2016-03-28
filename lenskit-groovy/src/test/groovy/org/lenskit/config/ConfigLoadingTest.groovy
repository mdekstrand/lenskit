/*
 * LensKit, an open source recommender systems toolkit.
 * Copyright 2010-2014 LensKit Contributors.  See CONTRIBUTORS.md.
 * Work on LensKit has been funded by the National Science Foundation under
 * grants IIS 05-34939, 08-08692, 08-12148, and 10-17697.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.lenskit.config


import org.lenskit.data.dao.EventCollectionDAO
import org.lenskit.data.dao.EventDAO
import org.grouplens.lenskit.vectors.similarity.PearsonCorrelation
import org.grouplens.lenskit.vectors.similarity.SignificanceWeightedVectorSimilarity
import org.grouplens.lenskit.vectors.similarity.VectorSimilarity
import org.junit.Test
import org.lenskit.LenskitConfiguration
import org.lenskit.LenskitRecommenderEngine
import org.lenskit.api.ItemScorer
import org.lenskit.baseline.ItemMeanRatingItemScorer
import org.lenskit.basic.ConstantItemScorer
import org.lenskit.basic.SimpleRatingPredictor
import org.lenskit.basic.TopNItemRecommender

import static org.hamcrest.Matchers.*
import static org.junit.Assert.assertThat

class ConfigLoadingTest {
    EventDAO dao = new EventCollectionDAO([])

    @Test
    void testLoadBasicConfig() {
        LenskitConfiguration config = ConfigHelpers.load {
            bind ItemScorer to ConstantItemScorer
            set ConstantItemScorer.Value to Math.PI
        }
        config.bind(EventDAO).to(dao)
        def engine = LenskitRecommenderEngine.build(config)
        def rec = engine.createRecommender()
        assertThat(rec.getItemScorer(), instanceOf(ConstantItemScorer))
        assertThat(rec.getItemRecommender(), instanceOf(TopNItemRecommender))
        assertThat(rec.getItemBasedItemRecommender(), nullValue());
        def bl = rec.itemScorer as ConstantItemScorer
        assertThat(bl.fixedScore, equalTo(Math.PI))
    }

    @Test
    void testEditBasicConfig() {
        LenskitConfiguration config = new LenskitConfiguration()
        config.set(ConstantItemScorer.Value).to(Math.PI)
        ConfigHelpers.configure(config) {
            bind ItemScorer to ConstantItemScorer
        }
        config.bind(EventDAO).to(dao)
        def engine = LenskitRecommenderEngine.build(config)
        def rec = engine.createRecommender()
        assertThat(rec.getItemScorer(), instanceOf(ConstantItemScorer))
        assertThat(rec.getItemRecommender(), instanceOf(TopNItemRecommender))
        assertThat(rec.getItemBasedItemRecommender(), nullValue());
        def bl = rec.itemScorer as ConstantItemScorer
        assertThat(bl.fixedScore, equalTo(Math.PI))
    }

    @Test
    void testLoadNewRoot() {
        LenskitConfiguration config = ConfigHelpers.load {
            root VectorSimilarity
            bind VectorSimilarity to PearsonCorrelation
        }
        config.bind(EventDAO).to(dao)
        def engine = LenskitRecommenderEngine.build(config)
        def rec = engine.createRecommender()
        assertThat(rec.getItemScorer(), nullValue());
        assertThat(rec.getItemRecommender(), nullValue())
        assertThat(rec.getItemBasedItemRecommender(), nullValue());
        assertThat(rec.get(VectorSimilarity),
                   instanceOf(PearsonCorrelation))
    }

    @Test
    void testLoadWithinBlock() {
        LenskitConfiguration config = ConfigHelpers.load {
            root VectorSimilarity
            bind VectorSimilarity to SignificanceWeightedVectorSimilarity
            within(VectorSimilarity) {
                bind VectorSimilarity to PearsonCorrelation
            }
        }
        config.bind(EventDAO).to(dao)
        def engine = LenskitRecommenderEngine.build(config)
        def rec = engine.createRecommender()
        assertThat(rec.getItemScorer(), nullValue());
        assertThat(rec.getItemRecommender(), nullValue())
        assertThat(rec.getItemBasedItemRecommender(), nullValue());
        def sim = rec.get(VectorSimilarity)
        assertThat(sim,
                   instanceOf(SignificanceWeightedVectorSimilarity))
        assertThat(sim.delegate, instanceOf(PearsonCorrelation))
    }

    @Test
    void testLoadBasicText() {
        LenskitConfiguration config = ConfigHelpers.load(
                """import org.lenskit.baseline.*
bind ItemScorer to ConstantItemScorer
set ConstantItemScorer.Value to Math.PI""");
        config.bind(EventDAO).to(dao)
        def engine = LenskitRecommenderEngine.build(config)
        def rec = engine.createRecommender()
        assertThat(rec.getItemScorer(), instanceOf(ConstantItemScorer))
        assertThat(rec.getItemRecommender(), instanceOf(TopNItemRecommender))
        assertThat(rec.getItemBasedItemRecommender(), nullValue());
        assertThat(rec.itemScorer.fixedScore, equalTo(Math.PI))
    }

    @Test
    void testLoadBasicURL() {
        LenskitConfiguration config = ConfigHelpers.load(getClass().getResource('test-config.groovy'))
        config.bind(EventDAO).to(dao)
        def engine = LenskitRecommenderEngine.build(config)
        def rec = engine.createRecommender()
        assertThat(rec.getItemScorer(), instanceOf(ConstantItemScorer))
        assertThat(rec.getItemRecommender(), instanceOf(TopNItemRecommender))
        assertThat(rec.getItemBasedItemRecommender(), nullValue());
        assertThat(rec.itemScorer.fixedScore, equalTo(Math.PI))
    }

    @Test
    void testLoadURLWithInclude() {
        LenskitConfiguration config = ConfigHelpers.load(getClass().getResource('test-include.groovy'))
        config.bind(EventDAO).to(dao)
        def engine = LenskitRecommenderEngine.build(config)
        def rec = engine.createRecommender()
        assertThat(rec.getItemScorer(), instanceOf(ConstantItemScorer))
        assertThat(rec.getItemRecommender(), instanceOf(TopNItemRecommender))
        assertThat(rec.getItemBasedItemRecommender(), nullValue());
        assertThat(rec.itemScorer.fixedScore, equalTo(Math.PI))
    }

    @Test
    void testModifyBasicText() {
        LenskitConfiguration config = new LenskitConfiguration()
        ConfigurationLoader loader = new ConfigurationLoader();
        LenskitConfigScript script = loader.loadScript(
                """
bind ItemScorer to ConstantItemScorer
set ConstantItemScorer.Value to Math.PI""");
        config.bind(EventDAO).to(dao)
        script.configure(config);
        def engine = LenskitRecommenderEngine.build(config)
        def rec = engine.createRecommender()
        assertThat(rec.getItemScorer(), instanceOf(ConstantItemScorer))
        assertThat(rec.getItemRecommender(), instanceOf(TopNItemRecommender))
        assertThat(rec.getItemBasedItemRecommender(), nullValue());
        assertThat(rec.itemScorer.fixedScore, equalTo(Math.PI))
    }

    @Test
    void testPreferenceDomain() {
        LenskitConfiguration config = ConfigHelpers.load {
            bind ItemScorer to ItemMeanRatingItemScorer
            domain minimum: 1, maximum: 5, precision: 0.5
        }
        config.bind(EventDAO).to(dao)
        def engine = LenskitRecommenderEngine.build(config)
        def rec = engine.createRecommender()
        assertThat(rec.getItemScorer(), instanceOf(ItemMeanRatingItemScorer));
        assertThat(rec.getItemRecommender(), instanceOf(TopNItemRecommender))
        def rp = rec.getRatingPredictor()
        assertThat(rp, anyOf(instanceOf(SimpleRatingPredictor)))
        assertThat((rp as SimpleRatingPredictor).scorer,
                   sameInstance(rec.getItemScorer()))
        def dom = (rp as SimpleRatingPredictor).preferenceDomain
        assertThat(dom, notNullValue());
        assertThat(dom.minimum, equalTo(1.0d))
        assertThat(dom.maximum, equalTo(5.0d))
        assertThat(dom.precision, equalTo(0.5d))
    }
}