package org.jboss.sv;

import javax.persistence.EntityManager;

/**
 * @author Stuart Douglas
 */
public interface EntityManagerProvider {

    EntityManager getEntityManager();

}
