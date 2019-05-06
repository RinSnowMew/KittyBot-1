package core.benchmark;

import utils.GlobalLog;

public class BenchmarkLog
{
	// Logging
	public static void Log(String str) { GlobalLog.Log("[Benchmark] " + str); }
	public static void Warn(String str) { GlobalLog.Warn("[Benchmark] " + str); }
	public static void Error(String str) { GlobalLog.Error(" [Benchmark] " + str); }
}
