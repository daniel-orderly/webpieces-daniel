package org.webpieces.router.api;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Named;
import javax.inject.Singleton;

import org.webpieces.util.cmdline2.Arguments;
import org.webpieces.util.cmdline2.CommandLineParser;
import org.webpieces.util.file.FileFactory;
import org.webpieces.util.file.VirtualFile;
import org.webpieces.util.security.SecretKeyInfo;

import com.google.common.collect.Lists;
import com.google.inject.Binder;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.util.Modules;

import io.micrometer.core.instrument.MeterRegistry;

public class RouterSvcFactory {

	public static final String APP_METRICS_KEY = "app.metrics";
	public static final String PLATFORM_METRICS_KEY = "platform.metrics";

    protected RouterSvcFactory() {}

    public static RouterService create(MeterRegistry metrics, VirtualFile routersFile, TemplateApi templateApi, Module routerOverrides) {
    		File baseWorkingDir = FileFactory.getBaseWorkingDir();
    		Arguments arguments = new CommandLineParser().parse();
    		RouterConfig config = new RouterConfig(baseWorkingDir)
    									.setMetaFile(routersFile)
    									.setSecretKey(SecretKeyInfo.generateForTest());
    		RouterService svc = create(metrics, config, templateApi, routerOverrides);
    		svc.configure(arguments);
    		arguments.checkConsumedCorrectly();
    		return svc;
    }
    
	public static RouterService create(MeterRegistry metrics, RouterConfig config, TemplateApi templateApi, Module routerOverrides) {
		List<Module> modules = getModules(metrics, config, templateApi);
		Module module = Modules.override(modules).with(routerOverrides);
		Injector injector = Guice.createInjector(module);
		RouterService svc = injector.getInstance(RouterService.class);
		return svc;	
	}


	public static List<Module> getModules(MeterRegistry metrics, RouterConfig config, TemplateApi templateApi) {
		List<Module> modules = new ArrayList<Module>();
		modules.addAll(getModules(config));
		modules.add(new ExtrasModule(metrics, templateApi));
		return modules;
	}
	
	public static List<Module> getModules(RouterConfig config) {
		List<Module> modules = Lists.newArrayList(new ProdRouterModule(config, new EmptyPortConfigLookup()));
		return modules;
	}
	
	private static class ExtrasModule implements Module {

		private MeterRegistry metrics;
		private TemplateApi templateApi;

		public ExtrasModule(MeterRegistry metrics, TemplateApi templateApi) {
			this.metrics = metrics;
			this.templateApi = templateApi;
		}

		@Singleton
		@Provides
		@Named(RouterSvcFactory.PLATFORM_METRICS_KEY)
		public MeterRegistry providePlatformMetrics(MeterRegistry base) {
			//install a default for platform metrics...
			return base;
		}

		@Singleton
		@Provides
		@Named(RouterSvcFactory.APP_METRICS_KEY)
		public MeterRegistry provideAppMetrics(MeterRegistry base) {
			return base;
		}

		@Override
		public void configure(Binder binder) {
			binder.bind(MeterRegistry.class).toInstance(metrics);
			binder.bind(TemplateApi.class).toInstance(templateApi);
		}
		
	}
}
