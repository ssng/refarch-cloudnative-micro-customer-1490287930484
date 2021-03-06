package customer;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.cloudant.client.api.ClientBuilder;
import com.cloudant.client.api.CloudantClient;
import com.cloudant.client.api.Database;
import com.cloudant.client.api.model.Response;
import com.cloudant.client.org.lightcouch.NoDocumentException;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;

import customer.config.CloudantPropertiesBean;
import customer.model.Customer;

/**
 * REST Controller to manage Customer database
 *
 */
@RestController
public class CustomerController {
    
    private static Logger logger =  LoggerFactory.getLogger(CustomerController.class);
    private Database cloudant;
    
    @Autowired
    private CloudantPropertiesBean cloudantProperties;
    
    @PostConstruct
    private void init() throws MalformedURLException {
        logger.debug(cloudantProperties.toString());
        
        try {
            logger.info("Connecting to cloudant at: " + "https://" + cloudantProperties.getHost() + ":" + cloudantProperties.getPort());
            final CloudantClient cloudantClient = ClientBuilder.url(new URL("https://" + cloudantProperties.getHost() + ":" + cloudantProperties.getPort()))
                    .username(cloudantProperties.getUsername())
                    .password(cloudantProperties.getPassword())
                    .build();
            
            cloudant = cloudantClient.database(cloudantProperties.getDatabase(), true);
            
            
            // create the design document if it doesn't exist
            if (!cloudant.contains("_design/username_searchIndex")) {
                final Map<String, Object> names = new HashMap<String, Object>();
                names.put("index", "function(doc){index(\"usernames\", doc.username); }");

                final Map<String, Object> indexes = new HashMap<>();
                indexes.put("usernames", names);

                final Map<String, Object> view_ddoc = new HashMap<>();
                view_ddoc.put("_id", "_design/username_searchIndex");
                view_ddoc.put("indexes", indexes);

                cloudant.save(view_ddoc);        
            }
            
        } catch (MalformedURLException e) {
            logger.error(e.getMessage(), e);
            throw e;
        }
        

    }
    
    private Database getCloudantDatabase()  {
        return cloudant;
    }
    
    /**
     * check
     */
    @RequestMapping("/check")
    @ResponseBody String check() {
        return "it works!";
    }
    
    /**
     * @return customer by username
     */
    @RequestMapping(value = "/customer/search", method = RequestMethod.GET)
    @ResponseBody ResponseEntity<?> searchCustomers(@RequestHeader Map<String, String> headers, @RequestParam(required=true) String username) {
        try {
        	
        	if (username == null) {
        		return ResponseEntity.badRequest().body("Missing username");
        	}
        	
        	final List<Customer> customers = getCloudantDatabase().findByIndex(
        			"{ \"selector\": { \"username\": \"" + username + "\" } }", 
        			Customer.class);
       	
        	//  query index
            return  ResponseEntity.ok(customers);
            
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        
    }

    

    /**
     * @return all customer
     */
    @HystrixCommand(fallbackMethod="failGood")
    @RequestMapping(value = "/customer", method = RequestMethod.GET)
    @ResponseBody ResponseEntity<?> getCustomers(@RequestHeader Map<String, String> headers) {
        try {
        	final String customerId = headers.get("ibm-app-user");
        	if (customerId == null) {
        		// if no user passed in, this is a bad request
        		return ResponseEntity.badRequest().body("Missing header: ibm-app-user");
        	}
        	
        	logger.info("caller: " + customerId);
			final Customer cust = getCloudantDatabase().find(Customer.class, customerId);
            
            return ResponseEntity.ok(cust);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        
    }

    /**
     * @return customer by id
     */
    @RequestMapping(value = "/customer/{id}", method = RequestMethod.GET)
    ResponseEntity<?> getById(@RequestHeader Map<String, String> headers, @PathVariable String id) {
        try {
			final String customerId = headers.get("ibm-app-user");
        	if (customerId == null) {
        		// if no user passed in, this is a bad request
        		return ResponseEntity.badRequest().body("Missing header: ibm-app-user");
        	}
        	
        	logger.debug("caller: " + customerId);
        	
        	if (!customerId.equals(id)) {
        		// if i'm getting a customer ID that doesn't match my own ID, then return 401
        		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        	}
        	
			final Customer cust = getCloudantDatabase().find(Customer.class, customerId);
            return ResponseEntity.ok(cust);
        } catch (NoDocumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Customer with ID " + id + " not found");
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Add customer 
     * @return transaction status
     */
    @RequestMapping(value = "/customer", method = RequestMethod.POST, consumes = "application/json")
    ResponseEntity<?> create(@RequestHeader Map<String, String> headers, @RequestBody Customer payload) {
        try {
        	// TODO: no one should have access to do this, it's not exposed to APIC
            final Database cloudant = getCloudantDatabase();
            
            if (payload.getCustomerId() != null && cloudant.contains(payload.getCustomerId())) {
                return ResponseEntity.badRequest().body("Id " + payload.getCustomerId() + " already exists");
            }
            
			final List<Customer> customers = getCloudantDatabase().findByIndex(
				"{ \"selector\": { \"username\": \"" + payload.getUsername() + "\" } }", 
				Customer.class);
 
			if (!customers.isEmpty()) {
                return ResponseEntity.badRequest().body("Customer with name " + payload.getUsername() + " already exists");
			}
           
            final Response resp = cloudant.save(payload);
            
            if (resp.getError() == null) {
				// HTTP 201 CREATED
				final URI location =  ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(resp.getId()).toUri();
				return ResponseEntity.created(location).build();
            } else {
            	return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(resp.getError());
            }

        } catch (Exception ex) {
            logger.error("Error creating customer: " + ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error creating customer: " + ex.toString());
        }
        
    }


    /**
     * Update customer 
     * @return transaction status
     */
    @RequestMapping(value = "/customer/{id}", method = RequestMethod.PUT, consumes = "application/json")
    ResponseEntity<?> update(@RequestHeader Map<String, String> headers, @PathVariable String id, @RequestBody Customer payload) {

        try {
			final String customerId = headers.get("ibm-app-user");
        	if (customerId == null) {
        		// if no user passed in, this is a bad request
        		return ResponseEntity.badRequest().body("Missing header: ibm-app-user");
        	}
        	
        	logger.info("caller: " + customerId);
			if (!customerId.equals("id")) {
        		// if i'm getting a customer ID that doesn't match my own ID, then return 401
        		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        	}

            final Database cloudant = getCloudantDatabase();
            final Customer cust = getCloudantDatabase().find(Customer.class, id);
    
            cust.setUsername(payload.getUsername());
            cust.setFirstName(payload.getFirstName());
            cust.setLastName(payload.getLastName());
            cust.setImageUrl(payload.getImageUrl());
            cust.setEmail(payload.getEmail());
           
            cloudant.save(payload);
        } catch (NoDocumentException e) {
            logger.error("Customer not found: " + id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Customer with ID " + id + " not found");
        } catch (Exception ex) {
            logger.error("Error updating customer: " + ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error updating customer: " + ex.toString());
        }
        
        return ResponseEntity.ok().build();
    }

    /**
     * Delete customer 
     * @return transaction status
     */
    @RequestMapping(value = "/customer/{id}", method = RequestMethod.DELETE)
    ResponseEntity<?> delete(@RequestHeader Map<String, String> headers, @PathVariable String id) {
		// TODO: no one should have access to do this, it's not exposed to APIC
    	
        try {
            final Database cloudant = getCloudantDatabase();
            final Customer cust = getCloudantDatabase().find(Customer.class, id);
            
            cloudant.remove(cust);
        } catch (NoDocumentException e) {
            logger.error("Customer not found: " + id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Customer with ID " + id + " not found");
        } catch (Exception ex) {
            logger.error("Error deleting customer: " + ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error deleting customer: " + ex.toString());
        }
        return ResponseEntity.ok().build();
    }

    private Iterable<Customer> failGood(@RequestHeader Map<String, String> headers) {
        // Simply return an empty array
        ArrayList<Customer> inventoryList = new ArrayList<Customer>();
        return inventoryList;
    }

    /**
     * @return Circuit breaker tripped
     */
    @HystrixCommand(fallbackMethod="failGood")
    @RequestMapping("/circuitbreaker")
    @ResponseBody
    public String tripCircuitBreaker() {
        System.out.println("Circuitbreaker Service invoked");
        return "";
    }
}
