package wings.v.byedpi;

public final class ByeDpiStrategyResult {

    public final String command;
    public int successCount;
    public int totalRequests;
    public boolean completed;

    public ByeDpiStrategyResult(String command) {
        this.command = command;
    }
}
