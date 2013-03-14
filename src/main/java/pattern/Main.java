/*
 * Copyright (c) 2007-2013 Concurrent, Inc. All Rights Reserved.
 *
 * Project and contact information: http://www.concurrentinc.com/
 */

package pattern;

import java.util.Properties;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cascading.flow.Flow;
import cascading.flow.FlowDef;
import cascading.flow.hadoop.HadoopFlowConnector;
import cascading.operation.AssertionLevel;
import cascading.operation.Debug;
import cascading.operation.DebugLevel;
import cascading.operation.aggregator.Average;
import cascading.operation.aggregator.Count;
import cascading.operation.assertion.AssertMatches;
import cascading.operation.expression.ExpressionFunction;
import cascading.pipe.Each;
import cascading.pipe.Every;
import cascading.pipe.GroupBy;
import cascading.pipe.Pipe;
import cascading.property.AppProps;
import cascading.scheme.hadoop.TextDelimited;
import cascading.tap.Tap;
import cascading.tap.hadoop.Hfs;
import cascading.tuple.Fields;


public class Main
  {
  /** Field LOG */
  private static final Logger LOG = LoggerFactory.getLogger( Main.class );

  /** @param args  */
  public static void main( String[] args ) throws RuntimeException
    {
    String ordersPath = args[ 0 ];
    String classifyPath = args[ 1 ];
    String trapPath = args[ 2 ];

    // set up the config properties
    Properties properties = new Properties();
    AppProps.setApplicationJarClass( properties, Main.class );
    HadoopFlowConnector flowConnector = new HadoopFlowConnector( properties );

    // create source and sink taps
    Tap ordersTap = new Hfs( new TextDelimited( true, "\t" ), ordersPath );
    Tap classifyTap = new Hfs( new TextDelimited( true, "\t" ), classifyPath );
    Tap trapTap = new Hfs( new TextDelimited( true, "\t" ), trapPath );
    Tap measureTap = null;

    // handle command line options
    OptionParser optParser = new OptionParser();
    optParser.accepts( "pmml" ).withRequiredArg();
    optParser.accepts( "measure" ).withRequiredArg();
    optParser.accepts( "rmse" ).withRequiredArg();
    optParser.accepts( "debug" );
    optParser.accepts( "assert" );

    OptionSet options = optParser.parse( args );
    Pipe classifyPipe = new Pipe( "classifyPipe" );
    String predictor = null;

    // define a "Classifier" model from the PMML description
    if( options.hasArgument( "pmml" ) )
      {
      String pmmlPath = (String) options.valuesOf( "pmml" ).get( 0 );
      ClassifierFunction classFunc = new ClassifierFunction( new Fields( "score" ), pmmlPath );
      classifyPipe = new Each( classifyPipe, classFunc.getInputFields(), classFunc, Fields.ALL );
      predictor = classFunc.getPredictor();
      }

    // optionally: measure the model results versus what was predicted
    // by another framework during model creation
    Pipe measurePipe = null;
    Pipe verifyPipe = null;

    if( options.hasArgument( "measure" ) )
      {
      String measurePath = (String) options.valuesOf( "measure" ).get( 0 );
      measureTap = new Hfs( new TextDelimited( true, "\t" ), measurePath );

      // add a stream assertion which implements a full regression test
      verifyPipe = new Pipe( "verify", classifyPipe );
      String expression = "predict.equals( score )";
      ExpressionFunction matchExpression = new ExpressionFunction( new Fields( "match" ), expression, String.class );
      verifyPipe = new Each( verifyPipe, Fields.ALL, matchExpression, Fields.ALL );
      verifyPipe = new Each( verifyPipe, DebugLevel.VERBOSE, new Debug( true ) );

      AssertMatches assertMatches = new AssertMatches( ".*true" );
      verifyPipe = new Each( verifyPipe, AssertionLevel.STRICT, assertMatches );

      // calculate a confusion matrix for the model results, assuming a "label" field
      Fields confusion = new Fields( "label", "score" );
      measurePipe = new Pipe( "measure", verifyPipe );
      measurePipe = new GroupBy( measurePipe, confusion );
      measurePipe = new Every( measurePipe, Fields.ALL, new Count(), Fields.ALL );
      }
    else if( options.hasArgument( "rmse" ) )
      {
      String measurePath = (String) options.valuesOf( "rmse" ).get( 0 );
      measureTap = new Hfs( new TextDelimited( true, "\t" ), measurePath );

      // calculate the RMSE for the model results
      String expression = "Math.pow( predict - score, 2.0 )";
      ExpressionFunction calcExpression = new ExpressionFunction( new Fields( "diff_sq" ), expression, Double.class );
      measurePipe = new Pipe( "measure", classifyPipe );
      measurePipe = new Each( measurePipe, Fields.ALL, calcExpression, Fields.ALL );

      measurePipe = new GroupBy( measurePipe, new Fields( predictor ) );
      measurePipe = new Every( measurePipe, new Fields( "diff_sq" ), new Average(), Fields.ALL );

      expression = "Math.sqrt( average )";
      calcExpression = new ExpressionFunction( new Fields( "rmse" ), expression, Double.class );
      measurePipe = new Each( measurePipe, Fields.ALL, calcExpression, new Fields( predictor, "rmse" ) );
      }

    // connect the taps, pipes, etc., into a flow
    FlowDef flowDef = FlowDef.flowDef().setName( "classify" )
      .addSource( classifyPipe, ordersTap )
      .addTrap( classifyPipe, trapTap );

    if( measurePipe != null )
      {
      flowDef.addSink( classifyPipe, classifyTap )
        .addTailSink( measurePipe, measureTap );

      if( verifyPipe != null )
        flowDef.addTrap( verifyPipe, trapTap );
      }
    else
      flowDef.addTailSink( classifyPipe, classifyTap );

    // set to DebugLevel.VERBOSE for trace, or DebugLevel.NONE
    // in production
    if( options.has( "debug" ) )
      flowDef.setDebugLevel( DebugLevel.VERBOSE );
    else
      flowDef.setDebugLevel( DebugLevel.NONE );

    // set to AssertionLevel.STRICT for all assertions, or
    // AssertionLevel.NONE in production
    if( options.has( "assert" ) )
      flowDef.setAssertionLevel( AssertionLevel.STRICT );
    else
      flowDef.setAssertionLevel( AssertionLevel.NONE );

    // write a DOT file and run the flow
    Flow classifyFlow = flowConnector.connect( flowDef );
    classifyFlow.writeDOT( "dot/classify.dot" );
    classifyFlow.complete();
    }
  }
