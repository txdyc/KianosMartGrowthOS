/**
 * Platform is an open module: commerce and content may depend on its internals
 * (auth, web error handling, audit, crypto, integration, queue, storage).
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.kiano.platform;
