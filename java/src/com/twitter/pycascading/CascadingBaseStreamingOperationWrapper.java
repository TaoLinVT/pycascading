/**
 * Copyright 2011 Twitter, Inc.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.twitter.pycascading;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.Iterator;

import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.JobConf;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

import cascading.flow.FlowProcess;
import cascading.flow.hadoop.HadoopFlowProcess;
import cascading.operation.BaseOperation;
import cascading.operation.OperationCall;
import cascading.tuple.Fields;
import cascading.tuple.TupleEntry;


import java.io.File;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.DataOutputStream;
import java.io.BufferedOutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import cascading.tuple.TupleEntryCollector;
import cascading.tuple.Tuple;
import cascading.tuple.TupleEntry;


/**
 * Wrapper for a Cascading BaseOperation that:
 *  * Launches and controls the sub process that streaming communicates with
 *  * Prepares the input tuples for a the streaming client process.
 *  * Parses the output of the client process for the generating of resulting tuples.
 * 
 * @author Ian O Connell
 */

@SuppressWarnings({ "rawtypes", "deprecation" })
public class CascadingBaseStreamingOperationWrapper extends BaseOperation implements Serializable {
  private static final long serialVersionUID = -535185466322890691L;
  
  // Buffer size used when dealing with the sub process
  private final static int BUFFER_SIZE = 128 * 1024; 

  // Streams for interacting with stdin/stdout of the streaming subprocess
  DataOutputStream stdoutStream = null;
  BufferedReader stdinStream = null;
  
  // streaming sub process object
  Process childProcess = null;
  
  // blocking queue to allow passing back stdout from the monitor thread
  BlockingQueue<ArrayList<Tuple>> childProcessOutputQueue;
  
  // Thread that reads from the stdout of the subprocess
  ChildOutputReader readerThread = null;
  
  // Command line string passed in that will give the streaming process
  protected String[] commandLine;

  protected String recordSeperator = "\r\n";
    
  public CascadingBaseStreamingOperationWrapper() {
    super();
  }

  public CascadingBaseStreamingOperationWrapper(Fields fieldDeclaration) {
    super(fieldDeclaration);
  }

  public CascadingBaseStreamingOperationWrapper(int numArgs) {
    super(numArgs);
  }

  public CascadingBaseStreamingOperationWrapper(int numArgs, Fields fieldDeclaration) {
    super(numArgs, fieldDeclaration);
  }

  
  /**
   * The init steps on each node, here we need to start the subprocess
   * and hook up the pipes so we can communicate with it
   * 
   */
  @Override
  public void prepare(FlowProcess flowProcess, OperationCall operationCall) {
    JobConf jobConf = ((HadoopFlowProcess) flowProcess).getJobConf();
    
    
    String sourceDir = null;
    if ("hadoop".equals(jobConf.get("pycascading.running_mode"))) {
      try {
        Path[] archives = DistributedCache.getLocalCacheArchives(jobConf);
        sourceDir = archives[1].toString() + "/";
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    } 
    
    
    try {
      ProcessBuilder builder = new ProcessBuilder(this.commandLine);
      builder.redirectErrorStream(true);
      if(sourceDir != null) {
        builder.directory(new File(sourceDir));
      }
      this.childProcess = builder.start();
    } catch (IOException e) {
        throw new RuntimeException(e);
    }
    this.stdoutStream = new DataOutputStream(new BufferedOutputStream(
                                                       childProcess.getOutputStream(),
                                                       BUFFER_SIZE));
    this.stdinStream = new BufferedReader(new InputStreamReader(childProcess.getInputStream()));
    childProcessOutputQueue = new ArrayBlockingQueue<ArrayList<Tuple>>(10);
    readerThread = new ChildOutputReader(stdinStream, childProcessOutputQueue);
    readerThread.start();
  }

  public int getNumParameters() {
    return 0;
  }


  /**
   * This handles dealing with the stdin/stdout streams for dealing with the child process
   * 
   * @return the standard out line we got from the child process for the stdin line
   */
  public void callFunction(String currentLine) {
    try {
      stdoutStream.write(currentLine.getBytes("UTF-8"));
      stdoutStream.write('\n');
      stdoutStream.flush();
    } catch (IOException e) {
        throw new RuntimeException(e);
    }
  }

  /**
   * This flushes the in memory thread safe queue of data we have that came back from the process
   * We bring it back to the main thread and then add it to the outputCollector
   * Here we presume the subprocess's output is tab seperated(bad)
   * We check to see if we are missing tuples at the end, if so we add null's
   *
   */
  public void flushOutput(TupleEntryCollector outputCollector) {
    try {
      ArrayList<Tuple> current = childProcessOutputQueue.poll(2, TimeUnit.MINUTES);
      if(current == null) {
        throw new RuntimeException("Timed out waiting for subprocess");
      }
      for (Tuple t : current) {
        outputCollector.add(t);
      }
    }
    catch (InterruptedException e) {} // If the process went away we just have no more tuples
  }
  
  public void nonBlockFlushOutput(TupleEntryCollector outputCollector) {
    ArrayList<Tuple> current = null;
    while((current = childProcessOutputQueue.poll()) != null)
    for (Tuple t : current) {
      outputCollector.add(t);
    }
  }
  
  /**
   * We call this when we have stopped passing data to the child process.
   * We close off the stdin pipe which should cause the process to gracefully exit.
   * After that we wait for thread to finish up and then flush the buffers one last time
   */
   
  public void finishOutput(TupleEntryCollector outputCollector) {
    try {
      stdoutStream.close();
    } catch (IOException e){}
    try {
      childProcess.waitFor();
    } catch (InterruptedException e) {}
    try {
      readerThread.join();
    } catch (InterruptedException e) {}
    nonBlockFlushOutput(outputCollector);
  }
  /**
   * Setter for the command line args to be called.
   * 
   * @param String
   *          the commandline 
   */
  public void setFunction(String[] commandLine) {
    this.commandLine = commandLine;
  }
  
  
   public void setRecordSeperators(String recordSeperator) {
    this.recordSeperator = recordSeperator;
    if (readerThread != null) {
      readerThread.setRecordSeperators(recordSeperator);
    }
  }
  

  /**
   * This Thread is launched to block on the stdout from the child process much as how the Hadoop Streaming class works.
   * This code is originally based on this with modifications as the Cascading collector cannot be presumed to be thread safe
   * unlike the Hadoop one. As such this thread writes to a thread safe queue which must be fetched from the main thread
   * and then written to the collector
   */
  class ChildOutputReader extends Thread {
    private BufferedReader outReader;
    private BlockingQueue<ArrayList<Tuple>> outCollector;
    private String recordSeperators;
    public void setRecordSeperators(String recordSeperators) {
      this.recordSeperators = recordSeperators;
    }
    ChildOutputReader(BufferedReader outReader, BlockingQueue<ArrayList<Tuple>> outCollector) {
        this(outReader, outCollector, "\n");
    }
    ChildOutputReader(BufferedReader outReader, BlockingQueue<ArrayList<Tuple>> outCollector, String recordSeperator) {
      setDaemon(true);
      this.outReader = outReader;
      this.outCollector = outCollector;
      this.recordSeperators = recordSeperator;
    }

    public void run() {
      ArrayList<Tuple> current_results_set = null;
      try {
        char[] inputChar = new char[1];
        int current_head_indx = 0;
        int current_tuple_indx = 0;
        StringBuilder currentBuffer = new StringBuilder();
        current_results_set = new ArrayList<Tuple>();
        while (outReader.read(inputChar) == 1) {
          if(recordSeperators.indexOf(inputChar[0]) >= 0 ) {
              if(currentBuffer.length() > 0) {
                Tuple result = new Tuple();
                result.add(current_tuple_indx);
                result.add(currentBuffer.toString());
                current_results_set.add(result);
                currentBuffer = new StringBuilder();
                current_tuple_indx = current_head_indx + 1;
              } else {
                outCollector.add(current_results_set);
                current_results_set = new ArrayList<Tuple>();
              }
          } else {
              currentBuffer.append(inputChar[0]);
          }
          current_head_indx++;
       }
      } catch (IOException ex) {
        if(current_results_set != null && current_results_set.size() > 0) {
          outCollector.add(current_results_set);
          current_results_set = null;
        }
      }
    }

  }


}
