package com.sandboni.core.engine;

import com.sandboni.core.engine.common.CachingSupplier;
import com.sandboni.core.engine.contract.Finder;
import com.sandboni.core.engine.exception.ParseRuntimeException;
import com.sandboni.core.engine.render.banner.BannerRenderService;
import com.sandboni.core.engine.result.FilterIndicator;
import com.sandboni.core.engine.sta.Builder;
import com.sandboni.core.engine.sta.Context;
import com.sandboni.core.engine.sta.connector.Connector;
import com.sandboni.core.engine.sta.operation.GraphOperations;
import com.sandboni.core.scm.GitInterface;
import com.sandboni.core.scm.exception.SourceControlException;
import com.sandboni.core.scm.proxy.filter.FileExtensions;
import com.sandboni.core.scm.scope.Change;
import com.sandboni.core.scm.scope.ChangeScope;
import com.sandboni.core.scm.scope.analysis.ChangeScopeAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

public class Processor  {

    private static final Logger log = LoggerFactory.getLogger(Processor.class);
    private final Arguments arguments;
    private final GitInterface changeDetector;
    private final Collection<Finder> finders;
    private final Collection<Connector> connectors;

    private final Supplier<Context> contextSupplier = new CachingSupplier<>(this::getContext);
    private final Supplier<ChangeScope<Change>> changeScopeSupplier = new CachingSupplier<>(this::getScope);
    private final Supplier<Builder> builderSupplier = new CachingSupplier<>(this::getBuilder);

    Processor(Arguments arguments, GitInterface changeDetector, Finder[] finders, Connector[] connectors) {
        this.arguments = arguments;
        this.changeDetector = changeDetector;
        this.finders = Collections.unmodifiableCollection(Arrays.asList(finders));
        this.connectors = Collections.unmodifiableCollection(Arrays.asList(connectors));

        //rendering Sandboni logo
        new BannerRenderService().render();
    }

    GitInterface getChangeDetector() {
        return changeDetector;
    }

    Collection<Finder> getFinders() {
        return finders;
    }
    Collection<Connector> getConnectors() {
        return connectors;
    }

    public Arguments getArguments() {
        return arguments;
    }

    public ResultGenerator getResultGenerator(){
        return new ResultGenerator(new GraphOperations(builderSupplier.get().getGraph(), contextSupplier.get()), arguments, builderSupplier.get().getFilterIndicator());
    }

    public Builder getGraphBuilder() {
        return getBuilder();
    }

    private synchronized Context getContext() {
        try {
            Instant start = Instant.now();
            Context context = createContext();
            Instant finish = Instant.now();
            log.debug("....Context creation execution total time: {}", Duration.between(start, finish).toMillis());
            return context;
        } catch (Exception e) {
            throw new ParseRuntimeException(e);
        }
    }

    private synchronized Builder getBuilder() {
        return getBuilder(contextSupplier.get());
    }

    /**
     * Returns true if first: is build stage or runAllExternalTests is false
     * then: (a) no change was made (b) change was made and contains at least one java file (not just cnfg files)
     *
     * @param changeChangeScope the change scope
     * @return boolean
     */
    private boolean proceed(ChangeScope<Change> changeChangeScope) {
        return (!isRunAllExternalTests() || !isIntegrationStage()) && (arguments.isRunSelectiveMode() ||
                changeChangeScope.isEmpty() ||
                ChangeScopeAnalyzer.analyzeConfigurationFiles(changeChangeScope, getBuildFiles()));
    }

    private boolean isRunAllExternalTests() {
        return arguments.isRunAllExternalTests();
    }

    private boolean isIntegrationStage() {
        return arguments.getStage().equals(Stage.INTEGRATION.getName());
    }

    public ChangeScope<Change> getScope(){
        try {
            return changeDetector.getChanges(arguments.getFromChangeId(), arguments.getToChangeId());
        } catch (SourceControlException e) {
            throw new ParseRuntimeException(e);
        }
    }

    private Context createContext(){
        return new Context(arguments.getApplicationId(), arguments.getSrcLocation(), arguments.getTestLocation(),
                arguments.getDependencies(), arguments.getFilter(), changeScopeSupplier.get());
    }

    private Builder getBuilder(Context context) {
        //proceed iff change scope contains at least one java file
        if (proceed(context.getChangeScope())) {
            context.getChangeScope().include(FileExtensions.JAVA, FileExtensions.FEATURE);
            log.info("Found changes: {}", context.getChangeScope());

            Instant start = Instant.now();
            finders.parallelStream().forEach(f -> f.findSafe(context));
            Instant finish = Instant.now();
            log.debug("....Finders execution total time: {}", Duration.between(start, finish).toMillis());
            start = Instant.now();
            connectors.parallelStream().filter(c-> c.proceed(context)).forEach(c -> c.connect(context));
            finish = Instant.now();
            log.debug("....Connectors execution total time: {}", Duration.between(start, finish).toMillis());
        } else if (isRunAllExternalTests() && isIntegrationStage()){
            log.info("Running All External Tests");
            finders.parallelStream().forEach(f -> f.findSafe(context));
            return new Builder(context, FilterIndicator.ALL_EXTERNAL);
        } else { //only cnfg files
            log.info("Found changes: {}", context.getChangeScope());
            log.info(" ** configuration file(s) were changed; All tests will be executed ** ");
            return new Builder(context, FilterIndicator.ALL);
        }
        return new Builder(context);
    }

    private static String[] getBuildFiles() {
        return new String[]{"pom.xml", "build.gradle"};
    }
}