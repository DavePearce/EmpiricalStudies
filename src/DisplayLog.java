import java.io.PrintStream;

public class DisplayLog {
	private final PrintStream output;
	private TimerFunction clock;
	private long startTime;
	
	public DisplayLog(PrintStream output) {
		this.output = output;
			
	}
	
	public void startActivity(String name) {
		this.clock = new TimerFunction(1000,new Runnable() {
			@Override
			public void run() {
				System.out.print(".");
			}				
		});	
		output.print(name);
		output.print(" ");
		clock.start();
		this.startTime = System.currentTimeMillis();
	}
	
	public void endActivity() {
		clock.terminate();
		long time = System.currentTimeMillis() - startTime;
		output.println(" (" + time + "ms)");
	}
	
	public class TimerFunction extends Thread {
		private Runnable fn;
		private long interval;
		private volatile boolean stopped;
		
		public TimerFunction(long interval, Runnable callback) {
			this.interval = interval;
			this.fn = callback;
		}
		
		@Override
		public void run() {
			while(!stopped) {
				try {
					fn.run();
					sleep(interval);
				} catch (InterruptedException e) {				
				}
			}
		}
		
		public void terminate() {
			this.stopped = true;
		}
	}

}
