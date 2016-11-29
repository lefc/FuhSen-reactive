/*
 * Copyright (C) 2014 EIS Uni-Bonn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package controllers;

import controllers.de.fuhsen.wrappers.security.TokenManager;
import play.mvc.*;
import views.html.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.io.OutputStream;

import oauth.signpost.OAuth;
import oauth.signpost.OAuthConsumer;
import oauth.signpost.OAuthProvider;
import oauth.signpost.basic.DefaultOAuthConsumer;
import oauth.signpost.basic.DefaultOAuthProvider;

import javax.xml.crypto.dsig.SignatureMethod;


public class Application extends Controller {

    private String keyword;

    public Result index() {
        return ok(index.render());
    }

    public Result TokenLifeLength(String wrapperId) {
        String life_length = "{ \"life_length\" : \""+TokenManager.getTokenLifeLength(wrapperId)+"\" }";
        return ok(life_length);
    }

    public Result results() {
        return ok(results.render());
    }

    public Result details() {
        return ok(details.render());
    }

    public Result getKeyword(){
        String json_res = "{ \"keyword\" : \""+this.keyword+"\" }";
        return ok(json_res);
    }

    public Result postRequest() {
        try
        {
            OAuthConsumer consumer = new DefaultOAuthConsumer(
                    "KpS6AIhMLWMR8yTgvxRQ5Oycq",
                    "wbZ69OmzFChSdvoBmYqIPMvj7Ry5iR5zICz7EB7jmHQDUZ4vW3");

            consumer.setTokenWithSecret("399064019-7i86JOLjsUfNRQQvn4siajRibVUpSS1jE9FXJ4Fh","KqLjT9i6P45SmOXjs7RzDi4IpSI6vwL5oEaTqMIYilwlo");

            URL url = new URL("https://api.twitter.com/1.1/users/search.json?count=100&q=Collarana");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("GET");
            consumer.sign(conn);

            if (conn.getResponseCode() != 200) {
                return ok("NOT OK "+conn.getResponseCode()+" "+conn.getResponseMessage());
            }
            else {
                BufferedReader br = new BufferedReader(new InputStreamReader(
                        (conn.getInputStream())));

                String finalOutput = "";
                String output;
                System.out.println("Output from Server .... \n");
                while ((output = br.readLine()) != null) {
                    finalOutput += output;
                }

                conn.disconnect();
                return ok("OK "+finalOutput);
            }

            /*URL url = new URL("https://us.jooble.org/api/6faa2158-1a6b-41da-952d-97ef5b7074b2");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Content-Length", "17");

            String input = "{keywords:'java'}";

            OutputStream os = conn.getOutputStream();
            os.write(input.getBytes());
            os.flush();

            if (conn.getResponseCode() != 200) {
                return ok("NOT OK "+conn.getResponseCode()+" "+conn.getResponseMessage());
            }
            else {
                BufferedReader br = new BufferedReader(new InputStreamReader(
                        (conn.getInputStream())));

                String finalOutput = "";
                String output;
                System.out.println("Output from Server .... \n");
                while ((output = br.readLine()) != null) {
                    finalOutput += output;
                }

                conn.disconnect();
                return ok("OK "+finalOutput);
            }*/
        }catch(Exception e) { return ok("NOT OK Exception "+e.getMessage()); }

    }


}


