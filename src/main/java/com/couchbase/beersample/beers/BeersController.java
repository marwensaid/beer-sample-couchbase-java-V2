
package com.couchbase.beersample.beers;

import com.couchbase.beersample.CouchbaseService;
import com.couchbase.client.java.document.JsonDocument;
import com.couchbase.client.java.document.json.JsonArray;
import com.couchbase.client.java.document.json.JsonObject;
import com.couchbase.client.java.error.DocumentAlreadyExistsException;
import com.couchbase.client.java.error.DocumentDoesNotExistException;
import com.couchbase.client.java.view.AsyncViewResult;
import com.couchbase.client.java.view.ViewResult;
import com.couchbase.client.java.view.ViewRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import rx.functions.Func1;

import java.util.Iterator;
import java.util.Map;

@RestController
@RequestMapping("/beer")
public class BeersController {

    private final CouchbaseService couchbaseService;

    @Autowired
    public BeersController(CouchbaseService couchbaseService) {
        this.couchbaseService = couchbaseService;
    }

    @RequestMapping(method = RequestMethod.GET, value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getBeer(@PathVariable String id) {
        JsonDocument doc = couchbaseService.read(id);
        if (doc != null) {
            return new ResponseEntity<String>(doc.content().toString(), HttpStatus.OK);
        } else {
            return new ResponseEntity<String>(HttpStatus.NOT_FOUND);
        }
    }

    @RequestMapping(method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> createBeer(@RequestBody Map<String, Object> beerData) {
        String id = "";
        try {
            JsonObject beer = parseBeer(beerData);
            id = "beer-" + beer.getString("name");
            JsonDocument doc = CouchbaseService.createDocument(id, beer);
            couchbaseService.create(doc);
            return new ResponseEntity<String>(id, HttpStatus.CREATED);
        } catch (IllegalArgumentException e) {
            return new ResponseEntity<String>(HttpStatus.BAD_REQUEST);
        } catch (DocumentAlreadyExistsException e) {
            return new ResponseEntity<String>("Id " + id + " already exist", HttpStatus.CONFLICT);
        } catch (Exception e) {
            return new ResponseEntity<String>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(method = RequestMethod.DELETE, value = "/{beerId}")
    public ResponseEntity<String> deleteBeer(@PathVariable String beerId) {
        JsonDocument deleted = couchbaseService.delete(beerId);
        return new ResponseEntity<String>("" + deleted.cas(), HttpStatus.OK);
    }

    @RequestMapping(value = "/{beerId}", consumes = MediaType.APPLICATION_JSON_VALUE, method = RequestMethod.PUT)
    public ResponseEntity<String> updateBeer(@PathVariable String beerId, @RequestBody Map<String, Object> beerData) {
        try {
            JsonObject beer = parseBeer(beerData);
            couchbaseService.update(CouchbaseService.createDocument(beerId, beer));
            return new ResponseEntity<String>(beerId, HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            return new ResponseEntity<String>(HttpStatus.BAD_REQUEST);
        } catch (DocumentDoesNotExistException e) {
            return new ResponseEntity<String>("Id " + beerId + " does not exist", HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            return new ResponseEntity<String>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private JsonObject parseBeer(Map<String, Object> beerData) {
        String type = (String) beerData.get("type");
        String name = (String) beerData.get("name");
        if (type == null || name == null || type.isEmpty() || name.isEmpty()) {
            throw new IllegalArgumentException();
        } else {
            JsonObject beer = JsonObject.create();
            for (Map.Entry<String, Object> entry : beerData.entrySet()) {
                beer.put(entry.getKey(), entry.getValue());
            }
            return beer;
        }
    }

    @RequestMapping(method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> listBeers(@RequestParam(required = false) Integer offset,
                                            @RequestParam(required = false) Integer limit) {
        ViewResult result = couchbaseService.findAllBeers(offset, limit);
        if (!result.success()) {
            //TODO maybe detect type of error and change error code accordingly
            return new ResponseEntity<String>(result.error().toString(), HttpStatus.INTERNAL_SERVER_ERROR);
        } else {
            JsonArray keys = JsonArray.create();
            Iterator<ViewRow> iter = result.rows();
            while (iter.hasNext()) {
                ViewRow row = iter.next();
                JsonObject beer = JsonObject.create();
                beer.put("name", row.key());
                beer.put("id", row.id());
                keys.add(beer);
            }
            return new ResponseEntity<String>(keys.toString(), HttpStatus.OK);
        }
    }

    @RequestMapping(method = RequestMethod.GET, value = "/search/{token}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> searchBeer(@PathVariable final String token) {
        //we'll get all beers asynchronously and compose on the stream to extract those that match
        AsyncViewResult viewResult = couchbaseService.findAllBeersAsync().toBlocking().single();
        if (viewResult.success()) {
            return couchbaseService.searchBeer(viewResult.rows(), token)
                    //transform the array into a ResponseEntity with correct status
                    .map(new Func1<JsonArray, ResponseEntity<String>>() {
                        @Override
                        public ResponseEntity<String> call(JsonArray objects) {
                            return new ResponseEntity<String>(objects.toString(), HttpStatus.OK);
                        }
                    })
                            //in case of errors during this processing, return a ERROR 500 response with detail
                    .onErrorReturn(new Func1<Throwable, ResponseEntity<String>>() {
                        @Override
                        public ResponseEntity<String> call(Throwable throwable) {
                            return new ResponseEntity<String>("Error while parsing results - " + throwable,
                                    HttpStatus.INTERNAL_SERVER_ERROR);
                        }
                    })
                            //block and send back the response
                    .toBlocking().single();
        } else {
            return new ResponseEntity<String>("Error while searching - " + viewResult.error(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
