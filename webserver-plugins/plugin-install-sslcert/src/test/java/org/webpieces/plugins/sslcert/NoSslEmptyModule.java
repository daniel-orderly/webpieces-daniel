package org.webpieces.plugins.sslcert;

import org.webpieces.plugins.fortesting.EmptyStorage;
import org.webpieces.router.api.extensions.SimpleStorage;

import com.google.inject.Binder;
import com.google.inject.Module;

public class NoSslEmptyModule implements Module {

	@Override
	public void configure(Binder binder) {
		binder.bind(SimpleStorage.class).toInstance(new EmptyStorage());
	}

}
