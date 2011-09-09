/*
 * LensKit, a reference implementation of recommender algorithms.
 * Copyright 2010-2011 Regents of the University of Minnesota
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
package org.grouplens.lenskit.knn.item;

import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import javax.annotation.concurrent.NotThreadSafe;

import org.grouplens.common.cursors.Cursor;
import org.grouplens.common.cursors.Cursors2;
import org.grouplens.lenskit.collections.LongSortedArraySet;
import org.grouplens.lenskit.core.RecommenderComponentBuilder;
import org.grouplens.lenskit.data.Event;
import org.grouplens.lenskit.data.UserHistory;
import org.grouplens.lenskit.data.dao.DataAccessObject;
import org.grouplens.lenskit.data.history.HistorySummarizer;
import org.grouplens.lenskit.data.history.UserVector;
import org.grouplens.lenskit.knn.OptimizableVectorSimilarity;
import org.grouplens.lenskit.knn.Similarity;
import org.grouplens.lenskit.knn.params.ItemSimilarity;
import org.grouplens.lenskit.knn.params.ModelSize;
import org.grouplens.lenskit.norm.VectorNormalizer;
import org.grouplens.lenskit.params.UserHistorySummary;
import org.grouplens.lenskit.params.UserVectorNormalizer;
import org.grouplens.lenskit.vector.ImmutableSparseVector;
import org.grouplens.lenskit.vector.MutableSparseVector;
import org.grouplens.lenskit.vector.SparseVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Build an item-item CF model from rating data.
 *
 * @author Michael Ekstrand <ekstrand@cs.umn.edu>
 *
 */
@NotThreadSafe
public class ItemItemModelBuilder extends RecommenderComponentBuilder<ItemItemModel> {
    private static final Logger logger = LoggerFactory.getLogger(ItemItemModelBuilder.class);

    private Similarity<? super SparseVector> itemSimilarity;

    private VectorNormalizer<? super UserVector> normalizer;
    private HistorySummarizer userSummarizer;
    private int modelSize;

    private DataAccessObject dao;

    public ItemItemModelBuilder(DataAccessObject dao) {
        this.dao = dao;
    }

    @ItemSimilarity
    public void setSimilarity(Similarity<? super SparseVector> similarity) {
        itemSimilarity = similarity;
    }

    @UserVectorNormalizer
    public void setNormalizer(VectorNormalizer<? super UserVector> normalizer) {
        this.normalizer = normalizer;
    }

    @UserHistorySummary
    public void setSummarizer(HistorySummarizer sum) {
        userSummarizer = sum;
    }

    public int getModelSize() {
        return modelSize;
    }
    @ModelSize
    public void setModelSize(int size) {
        modelSize = size;
    }

    @Override
    public ItemItemModel build() {
        ItemItemModelBuildStrategy similarityStrategy = createBuildStrategy(itemSimilarity);

        LongArrayList ilist = Cursors2.makeList(dao.getItems());
        LongSortedSet items = new LongSortedArraySet(ilist);

        Long2ObjectMap<LongSortedSet> userItemSets;
        if (similarityStrategy.needsUserItemSets())
            userItemSets = new Long2ObjectOpenHashMap<LongSortedSet>(items.size());
        else
            userItemSets = null;

        logger.debug("Building item data");
        Long2ObjectMap<Long2DoubleMap> itemData =
                buildItemRatings(items, userItemSets);
        // finalize the item data into vectors
        Long2ObjectMap<SparseVector> itemRatings =
                new Long2ObjectOpenHashMap<SparseVector>(itemData.size());
        ObjectIterator<Long2ObjectMap.Entry<Long2DoubleMap>> iter = itemData.long2ObjectEntrySet().iterator();
        while (iter.hasNext()) {
            Long2ObjectMap.Entry<Long2DoubleMap> entry = iter.next();
            Long2DoubleMap ratings = entry.getValue();
            SparseVector v = new ImmutableSparseVector(ratings);
            assert v.size() == ratings.size();
            itemRatings.put(entry.getLongKey(), v);
            entry.setValue(null);          // clear the array so GC can free
        }
        assert itemRatings.size() == itemData.size();

        ItemItemBuildContext context =
                new ItemItemBuildContext(items, itemRatings, userItemSets);
        ItemItemModelAccumulator accum =
                new ItemItemModelAccumulator(modelSize, items);
        similarityStrategy.buildMatrix(context, accum);

        return accum.build();
    }

    /**
     * Transpose the user matrix so we have a list of item
     * rating vectors.
     * @todo Fix this method to abstract item collection.
     * @todo Review and document this method.
     */
    private Long2ObjectMap<Long2DoubleMap>
    buildItemRatings(LongSortedSet items, Long2ObjectMap<LongSortedSet> userItemSets) {
        final boolean collectItems = userItemSets != null;
        final int nitems = items.size();

        // Create and initialize the transposed array to collect user
        Long2ObjectMap<Long2DoubleMap> workMatrix =
                new Long2ObjectOpenHashMap<Long2DoubleMap>(nitems);
        LongIterator iter = items.iterator();
        while (iter.hasNext()) {
            long iid = iter.nextLong();
            workMatrix.put(iid, new Long2DoubleOpenHashMap(20));
        }

        Cursor<? extends UserHistory<? extends Event>> histories =
                dao.getUserHistories(userSummarizer.eventTypeWanted());
        try {
            for (UserHistory<? extends Event> user: histories) {
                final long uid = user.getUserId();

                UserVector summary = userSummarizer.summarize(user);
                MutableSparseVector normed = summary.mutableCopy();
                normalizer.normalize(summary, normed);
                final int nratings = summary.size();

                // allocate the array ourselves to avoid an array copy
                long[] userItemArr = null;
                LongCollection userItems = null;
                if (collectItems) {
                    userItemArr = new long[nratings];
                    userItems = LongArrayList.wrap(userItemArr, 0);
                }

                for (Long2DoubleMap.Entry rating: normed.fast()) {
                    final long item = rating.getLongKey();
                    // get the item's rating vector
                    Long2DoubleMap ivect = workMatrix.get(item);
                    ivect.put(uid, rating.getDoubleValue());
                    if (userItems != null)
                        userItems.add(item);
                }
                if (collectItems) {
                    LongSortedSet itemSet = new LongSortedArraySet(userItemArr, 0, userItems.size());
                    userItemSets.put(uid, itemSet);
                }
            }
        } finally {
            histories.close();
        }

        return workMatrix;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    protected ItemItemModelBuildStrategy createBuildStrategy(Similarity<? super SparseVector> similarity) {
        if (similarity instanceof OptimizableVectorSimilarity) {
            return new SparseModelBuildStrategy((OptimizableVectorSimilarity) similarity);
        } else {
            return new SimpleModelBuildStrategy(similarity);
        }
    }
}
