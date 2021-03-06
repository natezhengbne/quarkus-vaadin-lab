package net.moewes.quarkus.vaadin;
//import com.vaadin.cdi.annotation.VaadinServiceEnabled;
//import com.vaadin.cdi.context.VaadinSessionScopedContext;

import static net.moewes.quarkus.vaadin.BeanLookup.SERVICE;

import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.PollEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.di.Instantiator;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationListener;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterListener;
import com.vaadin.flow.router.BeforeLeaveEvent;
import com.vaadin.flow.router.BeforeLeaveListener;
import com.vaadin.flow.router.ListenerPriority;
import com.vaadin.flow.server.ErrorHandler;
import com.vaadin.flow.server.ServiceDestroyEvent;
import com.vaadin.flow.server.ServiceException;
import com.vaadin.flow.server.SessionDestroyEvent;
import com.vaadin.flow.server.SessionInitEvent;
import com.vaadin.flow.server.SystemMessagesProvider;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServletService;
import com.vaadin.flow.server.VaadinSession;
import java.util.Optional;
import javax.enterprise.inject.AmbiguousResolutionException;
import javax.enterprise.inject.spi.BeanManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//import org.apache.deltaspike.core.util.ProxyUtils;

//import static com.vaadin.cdi.BeanLookup.SERVICE;

/**
 * Servlet service implementation for Vaadin CDI.
 * <p>
 * This class creates and initializes a @{@link VaadinServiceEnabled} {@link Instantiator}.
 * <p>
 * Some @{@link VaadinServiceEnabled} beans can be used to customize Vaadin, they are also created,
 * and bound if found.
 * <ul>
 * <li>{@link SystemMessagesProvider} is bound to service by
 * {@link VaadinService#setSystemMessagesProvider(SystemMessagesProvider)}.
 * <li>{@link ErrorHandler} is bound to created sessions by
 * {@link VaadinSession#setErrorHandler(ErrorHandler)}.
 * </ul>
 *
 * @see CdiVaadinServlet
 */
public class QuarkusVaadinServletService extends VaadinServletService {

  /**
   * Static listener class, to avoid registering the whole service instance.
   */
  @ListenerPriority(-100) // navigation event listeners are last by default
  private static class UIEventListener implements
      AfterNavigationListener,
      BeforeEnterListener,
      BeforeLeaveListener,
      ComponentEventListener<PollEvent> {

    private final BeanManager beanManager;

    private UIEventListener(BeanManager beanManager) {
      this.beanManager = beanManager;
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
      beanManager.fireEvent(event);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
      beanManager.fireEvent(event);
    }

    @Override
    public void beforeLeave(BeforeLeaveEvent event) {
      beanManager.fireEvent(event);
    }

    @Override
    public void onComponentEvent(PollEvent event) {
      beanManager.fireEvent(event);
    }

  }

  private final BeanManager beanManager;
  private final UIEventListener uiEventListener;

  public QuarkusVaadinServletService(QuarkusVaadinServlet servlet,
      DeploymentConfiguration configuration,
      BeanManager beanManager) {
    super(servlet, configuration);
    this.beanManager = beanManager;
    uiEventListener = new UIEventListener(beanManager);
  }

  @Override
  public void init() throws ServiceException {
    lookup(SystemMessagesProvider.class)
        .ifPresent(this::setSystemMessagesProvider);
    addUIInitListener(beanManager::fireEvent);
    addSessionInitListener(this::sessionInit);
    addSessionDestroyListener(this::sessionDestroy);
    addServiceDestroyListener(this::fireCdiDestroyEvent);
    super.init();
  }

  @Override
  public void fireUIInitListeners(UI ui) {
    ui.addAfterNavigationListener(uiEventListener);
    ui.addBeforeLeaveListener(uiEventListener);
    ui.addBeforeEnterListener(uiEventListener);
    ui.addPollListener(uiEventListener);
    super.fireUIInitListeners(ui);
  }

  @Override
  public QuarkusVaadinServlet getServlet() {
    return (QuarkusVaadinServlet) super.getServlet();
  }

  @Override
  protected Optional<Instantiator> loadInstantiators()
      throws ServiceException {
    Optional<Instantiator> instantiatorOptional = lookup(Instantiator.class);
    if (instantiatorOptional.isPresent()) {
      Instantiator instantiator = instantiatorOptional.get();
      if (!instantiator.init(this)) {
        /*
        Class unproxiedClass =
                ProxyUtils.getUnproxiedClass(instantiator.getClass());
        throw new ServiceException(
                "Cannot init VaadinService because "
                        + unproxiedClass.getName() + " CDI bean init()"
                        + " returned false.");
                        */
      }
    } else {
      throw new ServiceException(
          "Cannot init VaadinService "
              + "because no CDI instantiator bean found."
      );
    }
    return instantiatorOptional;
  }

  protected <T> Optional<T> lookup(Class<T> type) throws ServiceException {
    try {
      T instance = new BeanLookup<>(beanManager, type, SERVICE).lookup();
      return Optional.ofNullable(instance);
    } catch (AmbiguousResolutionException e) {
      throw new ServiceException(
          "There are multiple eligible CDI " + type.getSimpleName()
              + " beans.", e);
    }
  }

  private void sessionInit(SessionInitEvent sessionInitEvent)
      throws ServiceException {
    VaadinSession session = sessionInitEvent.getSession();
    lookup(ErrorHandler.class).ifPresent(session::setErrorHandler);
    beanManager.fireEvent(sessionInitEvent);
  }

  private void sessionDestroy(SessionDestroyEvent sessionDestroyEvent) {
    beanManager.fireEvent(sessionDestroyEvent);
   /*
    if (VaadinSessionScopedContext.guessContextIsUndeployed()) {
      // Happens on tomcat when it expires sessions upon undeploy.
      // beanManager.getPassivationCapableBean returns null for passivation id,
      // so we would get an NPE from AbstractContext.destroyAllActive
      getLogger().warn("VaadinSessionScoped context does not exist. " +
              "Maybe application is undeployed." +
              " Can't destroy VaadinSessionScopedContext.");
      return;
    }
    getLogger().debug("VaadinSessionScopedContext destroy");
    VaadinSessionScopedContext.destroy(sessionDestroyEvent.getSession());
    */
  }

  private void fireCdiDestroyEvent(ServiceDestroyEvent event) {
    try {
      beanManager.fireEvent(event);
    } catch (Exception e) {
      // During application shutdown on TomEE 7,
      // beans are lost at this point.
      // Does not throw an exception, but catch anything just to be sure.
      getLogger().warn("Error at destroy event distribution with CDI.",
          e);
    }
  }

  private static Logger getLogger() {
    return LoggerFactory.getLogger(QuarkusVaadinServletService.class);
  }

}


