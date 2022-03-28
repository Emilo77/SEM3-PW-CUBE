package concurrentcube;

import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;

public class Cube {
    private final int size;
    private final int[][][] sequence;
    BiConsumer<Integer, Integer> beforeRotation;
    BiConsumer<Integer, Integer> afterRotation;
    Runnable beforeShowing;
    Runnable afterShowing;

    /*Semafory*/
    private final Semaphore[] semLayers;
    private final Semaphore[] semAxis;
    private final Semaphore mutex;
    private final Semaphore firstThreads;

    /*Pomocnicze zmienne*/
    private final int[] waitingThreadsAxis;
    private int workingAxis;
    private int workingThreadsNumber;
    private int waitingAxisNumber;


    public Cube(int size,
                BiConsumer<Integer, Integer> beforeRotation,
                BiConsumer<Integer, Integer> afterRotation,
                Runnable beforeShowing,
                Runnable afterShowing) {

        this.size = size;
        this.beforeRotation = beforeRotation;
        this.afterRotation = afterRotation;
        this.beforeShowing = beforeShowing;
        this.afterShowing = afterShowing;

        int SIDESNUM = 6;
        this.sequence = new int[SIDESNUM][this.size][this.size];
        for (int side = 0; side < SIDESNUM; side++) {
            for (int row = 0; row < size; row++) {
                for (int column = 0; column < size; column++) {
                    sequence[side][row][column] = side;
                }
            }
        }

        this.semLayers = new Semaphore[size];
        this.mutex = new Semaphore(1, true);

        int STATESNUM = 4;
        this.semAxis = new Semaphore[STATESNUM];
        this.firstThreads = new Semaphore(0);
        this.workingAxis = -1;
        this.workingThreadsNumber = 0;
        this.waitingThreadsAxis = new int[STATESNUM];
        this.waitingAxisNumber = 0;

        for (int i = 0; i < size; i++) {
            semLayers[i] = new Semaphore(1, true);
        }
        for (int i = 0; i < STATESNUM; i++) {
            semAxis[i] = new Semaphore(0, true);
            waitingThreadsAxis[i] = 0;
        }
    }

    /*Funkcja pomocnicza do pobierania danych z kostki*/
    private int[] takeVertical(int side, int column) {
        int[] result = new int[size];
        for (int row = 0; row < size; row++) {
            result[row] = sequence[side][row][column];
        }
        return result;
    }

    /*Funkcja pomocnicza do pobierania danych z kostki*/
    private int[] takeHorizontal(int side, int row) {
        int[] result = new int[size];
        System.arraycopy(sequence[side][row], 0, result, 0, size);
        return result;
    }

    /*Funkcja pomocnicza do pobierania danych z kostki*/
    private int[] takeVerticalInverted(int side, int column) {
        int[] result = new int[size];
        for (int row = 0; row < size; row++) {
            result[row] = sequence[side][size - 1 - row][column];
        }
        return result;
    }

    /*Funkcja pomocnicza do pobierania danych z kostki*/
    private int[] takeHorizontalInverted(int side, int row) {
        int[] result = new int[size];
        for (int column = 0; column < size; column++) {
            result[column] = sequence[side][row][size - 1 - column];
        }
        return result;
    }

    /*Funkcja pomocnicza do obracania kostki*/
    private void changeVertical(int side, int column, int[] newColumn) {
        for (int row = 0; row < size; row++) {
            sequence[side][row][column] = newColumn[row];
        }
    }

    /*Funkcja pomocnicza do obracania kostki*/
    private void changeHorizontal(int side, int row, int[] newColumn) {
        if (size >= 0)
            System.arraycopy(newColumn, 0, sequence[side][row], 0, size);
    }

    /*Funkcja pomocnicza do obracania kostki*/
    private void rotateSideLeft(int side) {
        for (int i = 0; i < 3; i++) {
            rotateSideRight(side);
        }
    }

    /*Funkcja pomocnicza do obracania kostki*/
    private void rotateSideRight(int side) {
        int[][] copy = new int[size][size];

        for (int row = 0; row < size; row++) {
            System.arraycopy(sequence[side][row], 0, copy[row], 0, size);
        }

        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                sequence[side][row][column] = copy[size - 1 - column][row];
            }
        }
    }

    /*Protokół wstępny*/
    private void accessProtocol(int currentAxis) throws InterruptedException {

        mutex.acquireUninterruptibly();
        if (Thread.interrupted()) {
            mutex.release();
            throw new InterruptedException();
        }
        if (workingAxis == -1) {
            workingAxis = currentAxis;
        } else {
            if (workingAxis != currentAxis || waitingAxisNumber > 0) {
                waitingThreadsAxis[currentAxis]++;
                if (waitingThreadsAxis[currentAxis] == 1) {
                    waitingAxisNumber++;
                    mutex.release();
                    firstThreads.acquireUninterruptibly();
                    waitingAxisNumber--;
                    workingAxis = currentAxis;
                } else {
                    mutex.release();
                    try {
                        semAxis[currentAxis].acquire();
                    } catch (InterruptedException e) {
                        waitingThreadsAxis[currentAxis]--;
                        if (waitingThreadsAxis[currentAxis] > 0) {
                            semAxis[currentAxis].release();
                        } else {
                            mutex.release();
                        }
                        throw e;
                    }
                }
                waitingThreadsAxis[currentAxis]--;
            }
        }
        workingThreadsNumber++;
        if (waitingThreadsAxis[currentAxis] > 0) {
            semAxis[currentAxis].release();
        } else {
            mutex.release();
        }
    }

    /*Protokół końcowy*/
    private void exitProtocol() throws InterruptedException {
        mutex.acquireUninterruptibly();
        workingThreadsNumber--;
        if (workingThreadsNumber == 0) {
            if (waitingAxisNumber > 0) {
                firstThreads.release();
            } else {
                workingAxis = -1;
                mutex.release();
            }
        } else {
            mutex.release();
        }
    }

    /*Metoda obracania kostki*/
    public void rotate(int side, int layer) throws InterruptedException {
        int layerInverted = size - 1 - layer;
        int currentAxis = -1;

        switch (side) {
            case 0:
            case 5: {
                currentAxis = 0;
                break;
            }
            case 1:
            case 3: {
                currentAxis = 1;
                break;
            }
            case 2:
            case 4: {
                currentAxis = 2;
                break;
            }
        }

        accessProtocol(currentAxis);

        int actualLayer = layer;
        if (side == 3 || side == 4 || side == 5) {
            actualLayer = layerInverted;
        }
        try {
            semLayers[actualLayer].acquire();
        } catch (InterruptedException e) {
            semLayers[actualLayer].release();
            exitProtocol();
            throw e;
        }

        beforeRotation.accept(side, layer);
        switch (side) {
            case 0: {

                int[] coloursUp = takeHorizontal(4, layer);
                int[] coloursRight = takeHorizontal(3, layer);
                int[] coloursDown = takeHorizontal(2, layer);
                int[] coloursLeft = takeHorizontal(1, layer);

                changeHorizontal(4, layer, coloursLeft);
                changeHorizontal(3, layer, coloursUp);
                changeHorizontal(2, layer, coloursRight);
                changeHorizontal(1, layer, coloursDown);
                if (layer == 0) {
                    rotateSideRight(0);
                } else if (layer == size - 1) {
                    rotateSideLeft(5);
                }
                break;
            }

            case 1: {

                int[] coloursUp = takeVertical(0, layer);
                int[] coloursRight = takeVertical(2, layer);
                int[] coloursDown = takeVerticalInverted(5, layer);
                int[] coloursLeft = takeVerticalInverted(4, layerInverted);

                changeVertical(0, layer, coloursLeft);
                changeVertical(2, layer, coloursUp);
                changeVertical(5, layer, coloursRight);
                changeVertical(4, layerInverted, coloursDown);

                if (layer == 0) {
                    rotateSideRight(1);
                } else if (layer == size - 1) {
                    rotateSideLeft(3);
                }
                break;
            }

            case 2: {

                int[] coloursUp = takeHorizontal(0, layerInverted);
                int[] coloursRight = takeVerticalInverted(3, layer);
                int[] coloursDown = takeHorizontal(5, layer);
                int[] coloursLeft = takeVerticalInverted(1, layerInverted);

                changeHorizontal(0, layerInverted, coloursLeft);
                changeVertical(3, layer, coloursUp);
                changeHorizontal(5, layer, coloursRight);
                changeVertical(1, layerInverted, coloursDown);
                if (layer == 0) {
                    rotateSideRight(2);
                } else if (layer == size - 1) {
                    rotateSideLeft(4);
                }
                break;
            }
            case 3: {

                int[] coloursUp = takeVerticalInverted(0, layerInverted);
                int[] coloursRight = takeVerticalInverted(4, layer);
                int[] coloursDown = takeVertical(5, layerInverted);
                int[] coloursLeft = takeVertical(2, layerInverted);

                changeVertical(0, layerInverted, coloursLeft);
                changeVertical(4, layer, coloursUp);
                changeVertical(5, layerInverted, coloursRight);
                changeVertical(2, layerInverted, coloursDown);
                if (layer == 0) {
                    rotateSideRight(3);
                } else if (layer == size - 1) {
                    rotateSideLeft(1);
                }
                break;
            }
            case 4: {

                int[] coloursUp = takeHorizontalInverted(0, layer);
                int[] coloursRight = takeVertical(1, layer);
                int[] coloursDown = takeHorizontalInverted(5, layerInverted);
                int[] coloursLeft = takeVertical(3, layerInverted);

                changeHorizontal(0, layer, coloursLeft);
                changeVertical(1, layer, coloursUp);
                changeHorizontal(5, layerInverted, coloursRight);
                changeVertical(3, layerInverted, coloursDown);
                if (layer == 0) {
                    rotateSideRight(4);
                } else if (layer == size - 1) {
                    rotateSideLeft(2);
                }
                break;
            }
            case 5: {

                int[] coloursUp = takeHorizontal(2, layerInverted);
                int[] coloursRight = takeHorizontal(3, layerInverted);
                int[] coloursDown = takeHorizontal(4, layerInverted);
                int[] coloursLeft = takeHorizontal(1, layerInverted);

                changeHorizontal(2, layerInverted, coloursLeft);
                changeHorizontal(3, layerInverted, coloursUp);
                changeHorizontal(4, layerInverted, coloursRight);
                changeHorizontal(1, layerInverted, coloursDown);
                if (layer == 0) {
                    rotateSideRight(5);
                } else if (layer == size - 1) {
                    rotateSideLeft(0);
                }
                break;
            }
        }
        afterRotation.accept(side, layer);
        semLayers[actualLayer].release();
        exitProtocol();

    }

    /*Metoda wypisywania stanu kostki*/
    public String show() throws InterruptedException {
        int currentAxis = 3;

        accessProtocol(currentAxis);

        beforeShowing.run();
        StringBuilder result = new StringBuilder();
        for (int side = 0; side < 6; side++) {
            for (int row = 0; row < size; row++) {
                for (int column = 0; column < size; column++) {
                    result.append(sequence[side][row][column]);
                }
            }
        }
        afterShowing.run();

        exitProtocol();

        return result.toString();
    }
}
