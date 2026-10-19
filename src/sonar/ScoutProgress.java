package sonar;

/** Session-local unlock progress. Only confirmed entries after a victory advance it. */
final class ScoutProgress {
    private int blueLevelsLeft;

    ScoutProgress(int initialLevel) {
        if (initialLevel < 1) {
            throw new IllegalArgumentException("国际服初始关卡未识别，请确认画面后重新启动");
        }
        blueLevelsLeft = Math.max(0, 6 - initialLevel);
    }

    boolean blue(boolean preferBlue) {
        return preferBlue || blueLevelsLeft > 0;
    }

    void enteredNextLevel() {
        if (blueLevelsLeft > 0) {
            blueLevelsLeft--;
        }
    }
}
