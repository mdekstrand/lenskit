package org.lenskit.bench;

import org.grouplens.lenskit.data.text.EventFormat;
import org.grouplens.lenskit.data.text.Formats;
import org.grouplens.lenskit.data.text.TextEventDAO;
import org.lenskit.data.dao.EventDAO;
import org.lenskit.data.ratings.Rating;
import org.lenskit.util.io.ObjectStream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Warmup;

import java.io.File;

@Warmup(iterations = 5)
@Measurement(iterations = 10)
public class ReadRatingCSV {
    static File ratingsFile = new File("build/ml-100k/u.data");

    public double slurpRatings(EventDAO events) {
        double sum = 0;
        int n = 0;
        try (ObjectStream<Rating> ratings = events.streamEvents(Rating.class)) {
            for (Rating r: ratings) {
                sum += r.getValue();
                n += 1;
            }
        }
        return sum / n;
    }

    @Benchmark
    public void slurpRatingsNoOpt() {
        EventFormat fmt = Formats.ml100kFormat();
        EventDAO dao = TextEventDAO.create(ratingsFile, fmt);
        slurpRatings(dao);
    }

    @Benchmark
    public void slurpRatingsOptional() {
        EventFormat fmt = Formats.delimitedRatings("\t");
        EventDAO dao = TextEventDAO.create(ratingsFile, fmt);
        slurpRatings(dao);
    }
}