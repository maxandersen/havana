package nonblocking.aeron;

import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import nonblocking.BinaryStore;
import org.agrona.DirectBuffer;
import org.agrona.LangUtil;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;

import java.util.Arrays;

import static nonblocking.aeron.AeronSystem.AERON;
import static nonblocking.aeron.Constants.CACHE_IN_STREAM;
import static nonblocking.aeron.Constants.CHANNEL;

public class CacheStage implements Runnable, AutoCloseable {

   private final Subscription subscription;
   private final Thread thread;

   public CacheStage() {
      this.subscription =
         AERON.aeron.addSubscription(CHANNEL, CACHE_IN_STREAM);

      thread = new Thread(this);
      thread.start();
   }

   @Override
   public void run() {
      final IdleStrategy idleStrategy = Constants.CACHE_IN_IDLE_STRATEGY;

      // TODO wrap
      final FragmentHandler fragmentHandler = new CacheFragmentHandler();

      try {
         while (AERON.running.get()) {
            final int fragmentsRead =
               subscription.poll(fragmentHandler, Constants.FRAGMENT_LIMIT);

            idleStrategy.idle(fragmentsRead);
         }
      } catch (final Exception ex) {
         // TODO propagate error somehow
         LangUtil.rethrowUnchecked(ex);
      }
   }

   @Override
   public void close() throws InterruptedException {
      thread.join();
   }

   private static final class CacheFragmentHandler implements FragmentHandler {

      private final BinaryStore store = new BinaryStore();
      private final CacheReply reply = new CacheReply();

      @Override
      public void onFragment(DirectBuffer buffer, int offset, int length, Header header) {
         int index = offset;

         long correlationId = buffer.getLong(index);
         index += 8;

         byte method = buffer.getByte(index);
         index++;

         int keyLength = buffer.getInt(index);
         index += 4;

         byte[] key = new byte[keyLength];
         buffer.getBytes(index, key, 0, key.length);
         index += key.length;

         int valueLength = buffer.getInt(index);
         index += 4;

         byte[] value = new byte[valueLength];
         buffer.getBytes(index, value, 0, value.length);

         switch (method) {
            case 0:
               System.out.printf(
                  "[correlationId=%d] putIfAbsent(key=%s, value=%s)%n",
                  correlationId, Arrays.toString(key), Arrays.toString(value));

               boolean success = store.putIfAbsent(key, value);
               reply.complete(success, correlationId);
               return;
            default:
               System.err.println("Unexpected method: " + method);
         }
      }

   }


}