package org.testfun.jee;

import io.undertow.servlet.api.DeploymentInfo;
import org.jboss.resteasy.plugins.server.embedded.SecurityDomain;
import org.jboss.resteasy.plugins.server.embedded.SimplePrincipal;
import org.jboss.resteasy.plugins.server.undertow.UndertowJaxrsServer;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.jboss.resteasy.util.PortProvider;
import org.junit.rules.MethodRule;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;
import org.testfun.jee.runner.DependencyInjector;
import org.testfun.jee.runner.jaxrs.JaxRsException;
import org.testfun.jee.runner.jaxrs.RestRequest;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.security.Principal;

/**
 * A JUnit rule that launches a JAX-RS server (using RESTeasy and Undertow) running in the same JVM as the test itself.
 * Injection of EJBs and mocks into the JAX-RS resources requires running the test using the {@link EjbWithMockitoRunner} runner.
 */
public class JaxRsServer implements MethodRule {

    private int port = PortProvider.getPort();

    private UndertowJaxrsServer jaxRsServer;

    private Class[] resourceClasses;

    private Class[] providerClasses;

    private ExpectedClientResponseFailure expectedClientResponseFailure  = ExpectedClientResponseFailure.none();

    /**
     * Creates a JaxRsServer and deploys the specified resource classes.
     * @param resourceClasses one or more resource classes that should be deployed
     */
    public static JaxRsServer forResources(Class... resourceClasses) {
        return new JaxRsServer(resourceClasses);
    }

    private JaxRsServer(Class[] resourceClasses) {
        this.resourceClasses = resourceClasses;
    }

    /**
     * Optionally override the default selected port to bind to.
     * @param port TCP port to listen to
     * @return a new JaxRsServer
     */
    public JaxRsServer port(int port) {
        JaxRsServer newServer = new JaxRsServer(resourceClasses);
        newServer.port = port;
        newServer.providerClasses = this.providerClasses;
        return newServer;
    }

    public JaxRsServer providers(Class... providerClasses) {
        JaxRsServer newServer = new JaxRsServer(resourceClasses);
        newServer.port = this.port;
        newServer.providerClasses = providerClasses;
        return newServer;
    }

    /**
     * Gets the automatically-selected or manually-set TCP port used by the server.
     * @return selected TCP port
     */
    public int getPort() {
        return port;
    }

    /**
     * Constructs a new JSON REST request builder.
     * @param uri base request URI
     * @return REST request builder
     */
    public RestRequest jsonRequest(String uri) {
        return new RestRequest(uri, port).accept(MediaType.APPLICATION_JSON_TYPE);
    }

    /**
     * Constructs a new FORM REST request (application/x-www-form-urlencoded) builder.
     * @param uri base request URI
     * @return REST request builder
     */
    public RestRequest formRequest(String uri) {
        return new RestRequest(uri, port).accept(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
    }

    @Override
    public Statement apply(Statement base, FrameworkMethod method, Object target) {
        return new JaxRsServerStatement(expectedClientResponseFailure.apply(base, method, target));
    }

    /**
     * Set expectation for REST failure with a particular status code and a substring that should appear in the failure message.
     * @param expectedResponseStatus the HTTP status expected to be returned from the server
     * @param expectedMessageSubstring a substring of the expected message
     */
    public void expectFailureResponse(Response.Status expectedResponseStatus, String expectedMessageSubstring) {
        expectedClientResponseFailure.expectFailureResponse(expectedResponseStatus, expectedMessageSubstring);
    }

    public void startJaxRsServer() {
        jaxRsServer = new UndertowJaxrsServer().start();
        //todo find how to configure auth username/password
//        jaxRsServer.setSecurityDomain(new SecurityDomain() {
//            public Principal authenticate(String username, String password) throws SecurityException {
//                return new SimplePrincipal(username);
//            }
//
//            public boolean isUserInRole(Principal username, String role) {
//                return true;
//            }
//        });

        ResteasyDeployment deployment = new ResteasyDeployment();
        DeploymentInfo deploymentInfo = jaxRsServer.undertowDeployment(deployment, "/example");
        deploymentInfo.setClassLoader(getClass().getClassLoader());
        deploymentInfo.setDeploymentName("testfun");
        deploymentInfo.setContextPath("/");
        jaxRsServer.deploy(deploymentInfo);

        for (Class aClass : resourceClasses) {
            Object resourceInstance;
            try {
                resourceInstance = aClass.newInstance();
            } catch (Exception e1) {
                throw new IllegalArgumentException(e1);
            }
            DependencyInjector.getInstance().injectDependencies(resourceInstance);
            deployment.getRegistry().addSingletonResource(resourceInstance);
        }

        if (providerClasses != null) {
            for (Class providerClass: providerClasses) {
                deployment.getProviderFactory().registerProvider(providerClass);
            }
        }
    }

    public void shutdownJaxRsServer() {
        jaxRsServer.stop();
    }

    private class JaxRsServerStatement extends Statement {

        private final Statement next;

        private JaxRsServerStatement(Statement next) {
            this.next = next;
        }

        @Override
        public void evaluate() throws Throwable {
            startJaxRsServer();

            try {
                next.evaluate();

            } finally {
                shutdownJaxRsServer();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T, S> S getFromPrivateField(T obj, String fieldName) {
        // Locate the field in through all the super classes
        Field f = null;
        Class<?> objClass = obj.getClass();
        while(!objClass.equals(Object.class)) {
            try {
                f = objClass.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                // Ignore and try again with super
            }
            objClass = objClass.getSuperclass();
        }
        if (f == null) throw new JaxRsException("Could not find field '" + fieldName + "' in: " + obj);

        // Get the field's current accessibility
        boolean previousAccessState;
        try {
            previousAccessState = f.isAccessible();
        } catch (Exception e) {
            throw new JaxRsException("Could not get field's accessibility: " + fieldName, e);
        }

        // Change accessibility to true and get the field's value
        try {
            f.setAccessible(true);
            return (S)f.get(obj);
        } catch (Exception e) {
            throw new JaxRsException("Could not set field '" + fieldName + "'", e);
        } finally {

            // finally, restore field's accessibility
            f.setAccessible(previousAccessState);
        }
    }
}
