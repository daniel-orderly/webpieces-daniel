package org.webpieces.router.impl.loader;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.webpieces.router.impl.RouteMeta;

import com.google.inject.Injector;

@Singleton
public class ProdLoader implements Loader {

	private MetaLoader loader;
	
	@Inject
	public ProdLoader(MetaLoader loader) {
		this.loader = loader;
	}
	
	@Override
	public Class<?> clazzForName(String moduleName) {
		try {
			return Class.forName(moduleName);
		} catch (ClassNotFoundException e) {
			throw new IllegalArgumentException("Your clazz="+moduleName+" was not found on the classpath", e);
		}
	}
	
	private Object createController(Injector injector, String controllerClassFullName) {
		Class<?> clazz = clazzForName(controllerClassFullName);
		return injector.getInstance(clazz);
	}
	@Override
	public void loadControllerIntoMeta(RouteMeta meta, Injector injector, String controllerStr, String methodStr,
			boolean isInitializingAllControllers) {
		
		Object controllerInst = createController(injector, controllerStr);

		loader.loadInstIntoMeta(meta, controllerInst, methodStr);
	}

}
