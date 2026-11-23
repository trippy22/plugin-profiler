package io.github.pluginprofiler;

import net.runelite.api.Client;

/**
 * A point-in-time view of the RuneLite client thread's Java stack, captured by the
 * sampler worker with {@link Thread#getStackTrace()}. Frame zero is the innermost
 * call. The stack shows where the thread was executing or waiting at capture time.
 */
final class ClientThreadStack
{
    private static final ClientThreadStack EMPTY = new ClientThreadStack(new StackTraceElement[0]);
    private final StackTraceElement[] frames;

    ClientThreadStack(StackTraceElement[] frames)
    {
        this.frames = frames;
    }

    /** Looks up the current client thread for each sample. An unavailable thread yields an empty stack. */
    static ClientThreadStack capture(Client client)
    {
        return read(client.getClientThread());
    }

    static ClientThreadStack read(Thread thread)
    {
        return thread == null ? EMPTY : new ClientThreadStack(thread.getStackTrace());
    }

    int size()
    {
        return frames.length;
    }

    String classNameAt(int index)
    {
        return frames[index].getClassName();
    }

    /** Formats one class and method name for the in-memory call path. */
    String frameNameAt(int index)
    {
        StackTraceElement frame = frames[index];
        return frame.getClassName() + "." + frame.getMethodName();
    }
}
