package org.apache.logging.log4j.core.async.perftest;

import java.io.FileWriter;
import java.io.IOException;

import com.lmax.disruptor.collections.Histogram;

public class PerfTest {

	private static final String LINE100 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz1234567890!\"#$%&'()-=^~|\\@`[]{};:+*,.<>/?_123456";
	public static final String LINE500 = LINE100 + LINE100 + LINE100 + LINE100
			+ LINE100;

	static boolean verbose = false;
	static boolean throughput;

	// determine how long it takes to call System.nanoTime() (on average)
	static long calcNanoTimeCost() {
		final long iterations = 10000000;
		long start = System.nanoTime();
		long finish = start;

		for (int i = 0; i < iterations; i++) {
			finish = System.nanoTime();
		}

		if (finish <= start) {
			throw new IllegalStateException();
		}

		finish = System.nanoTime();
		return (finish - start) / iterations;
	}

	static Histogram createHistogram() {
		long[] intervals = new long[31];
		long intervalUpperBound = 1L;
		for (int i = 0, size = intervals.length - 1; i < size; i++) {
			intervalUpperBound *= 2;
			intervals[i] = intervalUpperBound;
		}

		intervals[intervals.length - 1] = Long.MAX_VALUE;
		return new Histogram(intervals);
	}

	public static void main(String[] args) throws Exception {
		new PerfTest().doMain(args);
	}

	public void doMain(String[] args) throws Exception {
		String runnerClass = args[0];
		IPerfTestRunner runner = (IPerfTestRunner) Class.forName(runnerClass)
				.newInstance();
		String name = args[1];
		String resultFile = args.length > 2 ? args[2] : null;
		for (String arg : args) {
			if ("-verbose".equalsIgnoreCase(arg)) {
				verbose = true;
			}
			if ("-throughput".equalsIgnoreCase(arg)) {
				throughput = true;
			}
		}
		int threadCount = args.length > 2 ? Integer.parseInt(args[3]) : 3;
		printf("Starting %s %s (%d)...%n", getClass().getSimpleName(), name,
				threadCount);
		runTestAndPrintResult(runner, name, threadCount, resultFile);
		runner.shutdown();
		System.exit(0);
	}

	public void runTestAndPrintResult(IPerfTestRunner runner,
			final String name, int threadCount, String resultFile)
			throws Exception {
		Histogram warmupHist = createHistogram();

		// ThreadContext.put("aKey", "mdcVal");
		println("Warming up the JVM...");
		long t1 = System.nanoTime();

		// warmup at least 2 rounds and at most 1 minute
		final long stop = System.currentTimeMillis() + (60 * 1000);
		for (int i = 0; i < 10; i++) {
			final int LINES = throughput ? 50000 : 200000;
			runTest(runner, LINES, null, warmupHist, 1);
			if (i > 0 && System.currentTimeMillis() >= stop) {
				return;
			}
		}

		printf("Warmup complete in %.1f seconds%n", (System.nanoTime() - t1)
				/ (1000.0 * 1000.0 * 1000.0));
		println("Waiting 10 seconds for buffers to drain warmup data...");
		Thread.sleep(10000);

		println("Starting the main test...");
		// test
		throughput = false;
		runSingleThreadedTest(runner, name, resultFile);

		Thread.sleep(1000);

		throughput = true;
		runSingleThreadedTest(runner, name, resultFile);
	}

	private int runSingleThreadedTest(IPerfTestRunner runner, String name,
			String resultFile) throws IOException {
		Histogram latency = createHistogram();
		final int LINES = throughput ? 50000 : 5000000;
		runTest(runner, LINES, "end", latency, 1);
		reportResult(resultFile, name, latency);
		return LINES;
	}

	static void reportResult(String file, String name, Histogram histogram)
			throws IOException {
		String result = createSamplingReport(name, histogram);
		println(result);

		if (file != null) {
			FileWriter writer = new FileWriter(file, true);
			writer.write(result);
			writer.write(System.getProperty("line.separator"));
			writer.close();
		}
	}

	static void printf(String msg, Object... objects) {
		if (verbose) {
			System.out.printf(msg, objects);
		}
	}

	static void println(String msg) {
		if (verbose) {
			System.out.println(msg);
		}
	}

	static String createSamplingReport(String name, Histogram histogram) {
		Histogram data = histogram;
		if (throughput) {
			return data.getMax() + " operations/second";
		}
		String result = String.format(
				"avg=%.0f 99%%=%d 99.99%%=%d sampleCount=%d", data.getMean(), //
				data.getTwoNinesUpperBound(), //
				data.getFourNinesUpperBound(), //
				data.getCount() //
				);
		return result;
	}

	public void runTest(IPerfTestRunner runner, int lines, String finalMessage,
			Histogram histogram, int threadCount) {
		if (throughput) {
			runner.runThroughputTest(lines, histogram);
		} else {
			long nanoTimeCost = calcNanoTimeCost();
			runner.runLatencyTest(lines, histogram, nanoTimeCost, threadCount);
		}
		if (finalMessage != null) {
			runner.log(finalMessage);
		}
	}
}
