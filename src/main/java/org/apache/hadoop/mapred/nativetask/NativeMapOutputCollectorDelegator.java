/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.mapred.nativetask;

import java.io.File;
import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.RawComparator;
import org.apache.hadoop.mapred.InvalidJobConfException;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapOutputCollector;
import org.apache.hadoop.mapred.TaskAttemptID;
import org.apache.hadoop.mapred.nativetask.handlers.NativeCollectorOnlyHandler;
import org.apache.hadoop.mapred.nativetask.serde.INativeSerializer;
import org.apache.hadoop.mapred.nativetask.serde.NativeSerialization;
import org.apache.hadoop.mapred.nativetask.util.ConfigUtil;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.server.jobtracker.JTConfig;
import org.apache.hadoop.util.QuickSort;
import org.apache.hadoop.util.RunJar;
import org.apache.pig.impl.util.ObjectSerializer;

public class NativeMapOutputCollectorDelegator<K, V> implements MapOutputCollector<K, V> {

  private static Log LOG = LogFactory.getLog(NativeMapOutputCollectorDelegator.class);
  private JobConf job;
  private NativeCollectorOnlyHandler<K, V> handler;

  private StatusReportChecker updater;

  @Override
  public void collect(K key, V value, int partition) throws IOException, InterruptedException {
    handler.collect(key, value, partition);
  }

  @Override
  public void close() throws IOException, InterruptedException {
    handler.close();
    if (null != updater) {
      updater.stop();
    }
  }

  @Override
  public void flush() throws IOException, InterruptedException, ClassNotFoundException {
    handler.flush();
  }

  @Override
  public void init(Context context) throws IOException, ClassNotFoundException {
    this.job = context.getJobConf();

    Platforms.init(job);

    if (job.getNumReduceTasks() == 0) {
      String message = "There is no reducer, no need to use native output collector";
      LOG.error(message);
      throw new InvalidJobConfException(message);
    }

    if (job.getClass(MRJobConfig.KEY_COMPARATOR, null, RawComparator.class) != null) {
      if (job.get(Constants.PIG_VERSION, null) != null) {
        String version = job.get(Constants.PIG_VERSION, null);
        if (version == null || !version.equals(job.get(Constants.EXPECTED_PIG_VERSION, null))) {
          String message = "Native output collector don't support Pig version " + version;
          LOG.error(message);
          throw new InvalidJobConfException(message);
        } else {
          LOG.info("Pig key types: Pig version " + version);
        }
      } else {
        String message = "Native output collector don't support customized java comparator "
            + job.get(MRJobConfig.KEY_COMPARATOR);
        LOG.error(message);
        throw new InvalidJobConfException(message);
      }
    }

    if (job.getBoolean(MRJobConfig.MAP_OUTPUT_COMPRESS, false) == true) {
      if (!isCodecSupported(job.get(MRJobConfig.MAP_OUTPUT_COMPRESS_CODEC))) {
        String message = "Native output collector don't support compression codec "
            + job.get(MRJobConfig.MAP_OUTPUT_COMPRESS_CODEC) + ", We support Gzip, Lz4, snappy";
        LOG.error(message);
        throw new InvalidJobConfException(message);
      }
    }

    if (!QuickSort.class.getName().equals(job.get(Constants.MAP_SORT_CLASS))) {
      String message = "Native-Task don't support sort class " + job.get(Constants.MAP_SORT_CLASS);
      LOG.error(message);
      throw new InvalidJobConfException(message);
    }

    final Class<?> keyCls = job.getMapOutputKeyClass();
    try {
      @SuppressWarnings("rawtypes")
      final INativeSerializer serializer = NativeSerialization.getInstance().getSerializer(keyCls);
      if (null == serializer) {
        String message = "Key type not supported. Cannot find serializer for " + keyCls.getName();
        LOG.error(message);
        throw new InvalidJobConfException(message);
      }

      if (job.getBoolean(Constants.PIG_GROUP_ONLY, false)) {
        LOG.info("Pig key types: group only");
      } else if ((serializer instanceof INativeComparable)
          && !job.getBoolean(Constants.PIG_USER_COMPARATOR, false)) {
        if (job.get(Constants.PIG_SORT_ORDER, null) != null) {
          boolean[] order = (boolean[]) ObjectSerializer.deserialize(job.get(Constants.PIG_SORT_ORDER));
          job.set(Constants.NATIVE_PIG_SORT, ConfigUtil.booleansToString(order));
          LOG.info("Pig key types: set sort order");
        }
        if (job.get(Constants.PIG_SEC_SORT_ORDER, null) != null) {
          boolean[] order = (boolean[]) ObjectSerializer.deserialize(job
              .get(Constants.PIG_SEC_SORT_ORDER));
          job.set(Constants.NATIVE_PIG_SEC_SORT, ConfigUtil.booleansToString(order));
          job.setBoolean(Constants.NATIVE_PIG_USE_SEC_KEY, true);
          LOG.info("Pig key types: set secondary sort order");
        }
      } else {
        String message = "Native output collector don't support this key, this key is not comparable in native "
            + keyCls.getName();
        LOG.error(message);
        throw new InvalidJobConfException(message);
      }
    } catch (final IOException e) {
      String message = "Cannot find serializer for " + keyCls.getName();
      LOG.error(message);
      throw new IOException(message);
    }

    final boolean ret = NativeRuntime.isNativeLibraryLoaded();
    if (ret) {
      NativeRuntime.configure(job);

      final long updateInterval = job.getLong(Constants.NATIVE_STATUS_UPDATE_INTERVAL,
          Constants.NATIVE_STATUS_UPDATE_INTERVAL_DEFVAL);
      updater = new StatusReportChecker(context.getReporter(), updateInterval);
      updater.start();

    } else {
      String message = "Nativeruntime cannot be loaded, please check the libnativetask.so is in hadoop library dir";
      LOG.error(message);
      throw new InvalidJobConfException(message);
    }

    String path = job.getJar();
    if (null != path) {
      Path jars = new Path(path).getParent();
    String libraryConf = job.get(Constants.NATIVE_CLASS_LIBRARY_CUSTOM, null);
    if (null != libraryConf) {
      String[] libraries = libraryConf.split(",");
      String[] pair;
      String jarDir = jars.toString();
      if (job.get(JTConfig.JT_IPC_ADDRESS).equals("local")) {
        File jobJar = new File(job.getJar().split(":")[1]);
        RunJar.unJar(jobJar, new File(jarDir));
      }
      for (int i = 0; i < libraries.length; i++) {
        pair = libraries[i].split("=");
        if (pair.length == 2) {
          LOG.info("Try to load library " + pair[0] + " with file " + pair[1]);
          if (NativeRuntime.registerLibrary(jarDir + "/lib/" + pair[1], pair[0]) != 0) {
            LOG.error("RegisterLibrary failed : name = " + pair[0] + " path = " + pair[1]);
          } else {
            LOG.info("RegisterLibrary success : name = " + pair[0] + " path = " + pair[1]);
          }
        }
      }
    }
    }

    this.handler = null;
    try {
      final Class<K> oKClass = (Class<K>) job.getMapOutputKeyClass();
      final Class<K> oVClass = (Class<K>) job.getMapOutputValueClass();
      final TaskAttemptID id = context.getMapTask().getTaskID();
      final TaskContext taskContext = new TaskContext(job, null, null, oKClass, oVClass,
          context.getReporter(), id);
      handler = NativeCollectorOnlyHandler.create(taskContext);
    } catch (final IOException e) {
      String message = "Native output collector cannot be loaded;";
      LOG.error(message);
      throw new IOException(message, e);
    }

    LOG.info("Native output collector can be successfully enabled!");
  }

  private boolean isCodecSupported(String string) {
    if ("org.apache.hadoop.io.compress.SnappyCodec".equals(string)
        || "org.apache.hadoop.io.compress.GzipCodec".equals(string)
        || "org.apache.hadoop.io.compress.Lz4Codec".equals(string)) {
      return true;
    }
    return false;
  }
}
