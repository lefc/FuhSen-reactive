/*
 * Copyright (C) 2016 EIS Uni-Bonn
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
package controllers.de.fuhsen.wrappers

import java.io._
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

import com.typesafe.config.ConfigFactory
import controllers.Application
import controllers.de.fuhsen.wrappers.dataintegration.{EntityLinking, SilkConfig, SilkTransformableTrait}
import controllers.de.fuhsen.wrappers.security.{RestApiOAuth2Trait, RestApiOAuthTrait}
import org.apache.jena.graph.Triple
import org.apache.jena.query.{Dataset, DatasetFactory, QueryExecutionFactory, QueryFactory}
import org.apache.jena.rdf.model.{Model, ModelFactory, Property, RDFNode, Resource, ResourceFactory, Statement, StmtIterator}
import org.apache.jena.riot.Lang
import org.apache.jena.sparql.core.Quad
import play.Logger
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{JsArray, JsString, Json}
import play.api.libs.oauth.OAuthCalculator
import play.api.libs.ws.{WSClient, WSRequest, WSResponse}
import play.api.mvc.{Action, Controller, Result}
import utils.dataintegration.RDFUtil._
import utils.dataintegration.{RDFUtil, RequestMerger, UriTranslator}
import controllers.de.fuhsen.common.{ApiError, ApiResponse, ApiSuccess}
import play.api.libs.json.Json.{parse, _}

import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._

/**
  * Handles requests to API wrappers. Wrappers must at least implement [[RestApiWrapperTrait]].
  * Depending on implemented traits also does transformation, linking and merging of entities.
  */
class WrapperController @Inject()(ws: WSClient) extends Controller {
  val requestCounter = new AtomicInteger(0)

  def search(wrapperId: String, query: String) = Action.async {
    WrapperController.wrapperMap.get(wrapperId) match {
      case Some(wrapper) =>
        Logger.info(s"Starting $wrapperId Search with query: " + query)
        execQueryAgainstWrapper(query, wrapper, None, None) map {
          case errorResult: ApiError =>
            InternalServerError(errorResult.errorMessage + " API status code: " + errorResult.statusCode)
          case success: ApiSuccess =>
            Ok(success.responseBody)
        }
      case None =>
        Future(NotFound("Wrapper " + wrapperId + " not found! Supported wrapper: " +
          WrapperController.sortedWrapperIds.mkString(", ")))
    }
  }

  def edsa_search(edsaWrapperId: String) = Action {
    val model_skills: Model = ModelFactory.createDefaultModel()
    model_skills.read("EDSA_docs/skillNames_temp.ttl")
    val skill_list = ListBuffer[String]()
    val skillsQuery = QueryFactory.create(
      s"""
         |PREFIX ns3: <http://www.edsa-project.eu/edsa#>
         |SELECT ?skill WHERE {
         |?s ns3:lexicalValue ?skill .
         |}
      """.stripMargin)
    val resultSet_skills = QueryExecutionFactory.create(skillsQuery, model_skills).execSelect()
    while (resultSet_skills.hasNext) {
      skill_list.append(resultSet_skills.next.get("skill").toString)
    }

    val model: Model = ModelFactory.createDefaultModel()
    model.read("EDSA_docs/countries_europe_eu_member_status.rdf")
    val country_list = ListBuffer[String]()

    val keywordQuery = QueryFactory.create(
      s"""
         |PREFIX gn:<http://www.geonames.org/ontology#>
         |SELECT ?country WHERE {
         |?s gn:name ?country .
         |}
      """.stripMargin)
    val resultSet = QueryExecutionFactory.create(keywordQuery, model).execSelect()
    while (resultSet.hasNext) {
      countryToISO8601(resultSet.next.get("country").toString) match {
        case Some(value) => country_list.append(value)
        case None =>
      }
    }
    val requestMerger = new RequestMerger()
    val wrapper = WrapperController.wrapperMap.get(edsaWrapperId).get
    for(x <- country_list ;y <- skill_list){
      var exists_next_page = true
      var page_count = 1
      while(exists_next_page) {
        var res = Await.result(execQueryAgainstWrapper(y, wrapper, Option(page_count.toString), Option(x)), 10 second) //Duration.Inf, we could wait infinitely with ths, but is better to have an upper boundary.
        res match {
          case ApiSuccess(responseBody) =>
            var current_model = rdfStringToModel(responseBody, Lang.TURTLE.getName)
            val countQuery = QueryFactory.create(
              s"""
                 |PREFIX el:<http://www.semanticweb.org/elisasibarani/ontologies/2016/0/untitled-ontology-51#>
                 |SELECT (COUNT(DISTINCT ?id) as ?count)
                 |WHERE {?s el:id ?id .
                 |}
                  """.stripMargin)
            val count_ids = QueryExecutionFactory.create(countQuery, current_model).execSelect()
            count_ids.next.getLiteral("count").getValue.asInstanceOf[Int] compare wrapper.max_results match {
              case 0 => page_count += 1
              case -1 => exists_next_page = false
              case 1 => page_count += 1 //No deberia pasar nunca.
            }
            requestMerger.addWrapperResult(geonamesEnrichment(current_model), wrapper.sourceUri)
        }
      }
    }
    val json_model = requestMerger.serializeMergedModel(Lang.JSONLD)
    val pw = new PrintWriter(new File("EDSA_docs/complete.ttl"))
    pw.write(json_model)
    pw.close
    Ok(json_model)
  }

  private def geonamesEnrichment(model: Model): Model = {
    var iter:StmtIterator = model.listStatements(null, model.createProperty("http://schema.org/jobLocation"), null);

    val future_statements = ListBuffer[Future[Model]]()
    val complete_model = ModelFactory.createDefaultModel()

    while (iter.hasNext()) {
      var stmt:Statement      = iter.nextStatement();  // get next statement
      var subject:Resource   = stmt.getSubject();     // get the subject
      var predicate:Property = stmt.getPredicate();   // get the predicate
      var rdf_object:RDFNode = stmt.getObject();      // get the object

      val future_stm =
          ws.url(ConfigFactory.load.getString("geonames.search.url"))
          .withQueryString("q"->rdf_object.asLiteral().getString,
                           "formatted"-> "true",
                           "maxRows"->"10",
                           "username"->"camilom",
                           "style"->"full").get.map(
          response => {
            val new_model = ModelFactory.createDefaultModel()
            val lat = (((Json.parse(response.body) \ "geonames") (0) \ "lat").get).toString()
            val lng = (((Json.parse(response.body) \ "geonames") (0) \ "lng").get).toString()

            new_model.add(ResourceFactory.createStatement(subject, ResourceFactory.createProperty("http://schema.org/jobLocation_fuhsen_LAT"), ResourceFactory.createTypedLiteral(lat)))
            new_model.add(ResourceFactory.createStatement(subject, ResourceFactory.createProperty("http://schema.org/jobLocation_fuhsen_LNG"), ResourceFactory.createTypedLiteral(lng)))
            new_model
          }
        )

      future_statements += future_stm

      future_stm.map {
        res => {
          complete_model.add(res)
        }
      }

    }

    val f = Future.sequence(future_statements)
    Await.ready(f, Duration.Inf)

    complete_model.add(model)
  }

  private def countryToISO8601(country: String): Option[String] = {
    country match {
      //case "United Kingdom" => Some("gb")
      //case "Germany" => Some("de")
      case "France" => Some("fr")
      //case "Netherlands" => Some("nl")
      //case "Poland" => Some("pl")
      //case "Russia" => Some("ru")
      case _ => None
    }
  }

  private def execQueryAgainstWrapper(query: String, wrapper: RestApiWrapperTrait, page: Option[String], country: Option[String]): Future[ApiResponse] = {
    val apiRequest = createApiRequest(wrapper, query, page: Option[String], country: Option[String])
    val apiResponse = executeApiRequest(apiRequest, wrapper)
    val customApiResponse = customApiHandling(wrapper, apiResponse)
    transformApiResponse(wrapper, customApiResponse, query, country, page)
  }

  /**
    * Returns the merged result from multiple wrappers in N-Quads format.
    *
    * @param query      for each wrapper
    * @param wrapperIds a comma-separated list of wrapper ids
    */
  def searchMultiple(query: String, wrapperIds: String) = Action.async {
    val wrappers = (wrapperIds.split(",") map (WrapperController.wrapperMap.get)).toSeq
    if (wrappers.exists(_.isEmpty)) {
      Future(BadRequest("Invalid wrapper requested! Supported wrappers: " +
        WrapperController.sortedWrapperIds.mkString(", ")))
    } else {
      fetchAndIntegrateWrapperResults(wrappers, query)
    }
  }

  /**
    * Returns the merged result from multiple wrappers in JSON-LD format.
    *
    * @param query for each wrapper
    * @param wrapperIds a comma-separated list of wrapper ids
    */
  def searchMultiple2(query: String, wrapperIds: String) = Action.async {
    val wrappers = (wrapperIds.split(",") map (WrapperController.wrapperMap.get)).toSeq
    if(wrappers.exists(_.isEmpty)) {
      Future(BadRequest("Invalid wrapper requested! Supported wrappers: " +
        WrapperController.sortedWrapperIds.mkString(", ")))
    } else {
      val requestMerger = new RequestMerger()
      val resultFutures = wrappers.flatten map (wrapper => execQueryAgainstWrapper(query, wrapper, None, None))
      Future.sequence(resultFutures) map { results =>
        for ((wrapperResult, wrapper) <- results.zip(wrappers.flatten)) {
          wrapperResult match {
            case ApiSuccess(responseBody) => Logger.debug("POST-SILK:"+responseBody)
              val model = rdfStringToModel(responseBody, Lang.TURTLE.getName) //Review
              requestMerger.addWrapperResult(model, wrapper.sourceUri)
            case _: ApiError =>
          }
        }
        //val resultDataset = requestMerger.constructQuadDataset()
        Ok(requestMerger.serializeMergedModel(Lang.NTRIPLES))
      }
    }
  }


  /**
    * Link and merge entities from different sources.
    *
    * @param wrappers
    * @param query
    * @return
    */
  private def fetchAndIntegrateWrapperResults(wrappers: Seq[Option[RestApiWrapperTrait]],
                                              query: String): Future[Result] = {
    // Fetch the transformed results from each wrapper
    val resultFutures = wrappers.flatten map (wrapper => execQueryAgainstWrapper(query, wrapper, None, None))
    Future.sequence(resultFutures) flatMap { results =>
      // Merge results
      val requestMerger = mergeWrapperResults(wrappers, results)
      // Link entities
      val sameAsTriples = personLinking(requestMerger.serializeMergedModel(Lang.TURTLE), langToAcceptType(Lang.TURTLE))
      val resultDataset = requestMerger.constructQuadDataset()
      // Rewrite/merge entities based on entity linking
      val rewrittenDataset = rewriteDatasetBasedOnSameAsLinks(resultDataset, sameAsTriples)
      datasetToNQuadsResult(rewrittenDataset)
    }
  }

  private def datasetToNQuadsResult(rewrittenDataset: Future[Dataset]): Future[Result] = {
    rewrittenDataset map { d =>
      Ok(datasetToQuadString(d, Lang.JSONLD)).
        withHeaders(("content-type", Lang.JSONLD.getContentType.getContentType))
    }
  }

  private def mergeWrapperResults(wrappers: Seq[Option[RestApiWrapperTrait]],
                                  results: Seq[ApiResponse]): RequestMerger = {
    val requestMerger = new RequestMerger()
    for ((wrapperResult, wrapper) <- results.zip(wrappers.flatten)) {
      wrapperResult match {
        case ApiSuccess(responseBody) =>
          val model = rdfStringToModel(responseBody, Lang.JSONLD.getName)
          requestMerger.addWrapperResult(model, wrapper.sourceUri)
        case _: ApiError =>
        // Ignore for now
      }
    }
    requestMerger
  }

  /**
    * Rewrites the entity URIs based on the sameAs links. All entities of each transitive closure will
    * have the same URI and point to the original URI via sameAs link (one per rewritten entity and source graph).
    *
    * @param inputDataset
    * @param sameAs
    * @return
    */
  private def rewriteDatasetBasedOnSameAsLinks(inputDataset: Dataset,
                                               sameAs: Future[Option[Traversable[Triple]]]): Future[Dataset] = {
    sameAs map {
      case Some(sameAsTriples) =>
        val it = inputDataset.asDatasetGraph().find()
        val quads = ArrayBuffer.empty[Quad]
        while(it.hasNext) {
          quads.append(it.next())
        }
        rewriteDatasetBasedOnSameAsLinks(sameAsTriples, quads)
      case None =>
        inputDataset
    }
  }

  private def rewriteDatasetBasedOnSameAsLinks(sameAsTriples: Traversable[Triple],
                                               quads: ArrayBuffer[Quad]): Dataset = {
    val translatedQuads = UriTranslator.translateQuads(
      quads = quads.toTraversable,
      links = sameAsTriples
    )
    val translatedDataset = DatasetFactory.create()
    val datasetGraph = translatedDataset.asDatasetGraph()
    for (quad <- translatedQuads) {
      datasetGraph.add(quad)
    }
    translatedDataset
  }

  def personLinking(entityRDF: String, acceptType: String): Future[Option[Traversable[Triple]]] = {
    executePersonLinking(entityRDF, acceptType) map {
      case ApiSuccess(body) =>
        Some(stringToTriple(body, acceptTypeToRdfLang(acceptType)))
      case ApiError(status, message) =>
        Logger.warn(s"Person linking service returned a status code of $status")
        None
    }
  }

  /** If a custom response handling is defined execute it against the response if has not been an error */
  private def customApiHandling(wrapper: RestApiWrapperTrait,
                                apiResponse: Future[ApiResponse]): Future[ApiResponse] = {
    wrapper.customResponseHandling(ws) match {
      case Some(customFn) =>
        apiResponse.flatMap {
          case ApiSuccess(body) =>
            customFn(body).
              map(customResult => ApiSuccess(customResult))
          case r: ApiError =>
            Future(r)
        }
      case None =>
        apiResponse
    }
  }

  /** Handles transformation if configured for the wrapper */
  private def transformApiResponse(wrapper: RestApiWrapperTrait,
                                   apiResponse: Future[ApiResponse],
                                   query: String,
                                   country: Option[String],
                                   page: Option[String]): Future[ApiResponse] = {
    if(wrapper.sourceLocalName.equals("jooble")) {
      val joobleResponse = new Application().postRequest(query, country.get, page.get)
      Logger.debug("PRE-SILK (JOOBLE): "+joobleResponse )
      handleSilkTransformation(wrapper, joobleResponse.replace("\\r", ""))
    }else{
      Logger.debug("PRE-SILK: "+ apiResponse.value )
      apiResponse.flatMap {
        case error: ApiError =>
          // There has been an error previously, don't go on.
          Future(error)
        case ApiSuccess(body) =>
          if(wrapper.sourceLocalName.equals("indeed")){
            val bodyS = body.replace("<?xml version='1.0' encoding='UTF-8'?>","")
            Logger.debug("PRE-SILK: "+bodyS)
            handleSilkTransformation(wrapper, bodyS)
          } else{
            Logger.debug("PRE-SILK: "+body)
            handleSilkTransformation(wrapper, body)
          }
      }
    }
  }

  /** Executes the request to the wrapped REST API */
  private def executeApiRequest(apiRequest: WSRequest, wrapper: RestApiWrapperTrait): Future[ApiResponse] = {
    if(wrapper.requestType.equals("POST")){
      Logger.info("POST wrapper request")
      //apiRequest.withHeaders("Content-Type"->"application/x-www-form-urlencoded", "Content-Length"->"31").post("{'keywords': 'account manager'}").map(convertToApiResponse("Wrapper or the wrapped service"))
      null
    }else{
      Logger.info("GET wrapper request")
      Logger.info(apiRequest.url)
      apiRequest.get.map(convertToApiResponse("Wrapper or the wrapped service"))
    }
  }

  /** If transformations are configured then execute them via the Silk REST API */
  def handleSilkTransformation(wrapper: RestApiWrapperTrait,
                               content: String,
                               acceptType: String = "text/turtle"): Future[ApiResponse] = {
    //acceptType: String = "text/csv"): Future[ApiResponse] = {
    wrapper match {
      case silkTransform: SilkTransformableTrait if silkTransform.silkTransformationRequestTasks.size > 0 =>
        Logger.info("Execute Silk Transformations")
        val lang = acceptTypeToRdfLang(acceptType)
        val futureResponses = executeTransformation(content, acceptType, silkTransform)
        val rdf = convertToRdf(lang, futureResponses)
        rdf.map(content => ApiSuccess(content))
      case _ =>
        // No transformation to be executed
        Future(ApiSuccess(content))
    }
  }

  /** Execute all transformation tasks on the content */
  private def executeTransformation(content: String,
                                    acceptType: String,
                                    silkTransform: RestApiWrapperTrait with SilkTransformableTrait): Seq[Future[ApiResponse]] = {
    for (transform <- silkTransform.silkTransformationRequestTasks) yield {
      Logger.info("Executing silk transformation: "+transform.transformationTaskId)
      //val task = silkTransform.silkTransformationRequestTasks.head
      val transformRequest = ws.url(silkTransform.transformationEndpoint(transform.transformationTaskId))
        //.withHeaders("Content-Type" -> "application/xml; charset=utf-8")
        //.withHeaders("Content-Type" -> "application/json; charset=utf-8")
        .withHeaders("Accept" -> "application/json; charset=utf-8")
        //.withHeaders("Accept" -> acceptType)
      val response = transformRequest
        .post(transform.silkTransformationRequestBodyGenerator(content))
        .map(convertToApiResponse("Silk transformation endpoint"))
      response
    }
  }

  private def convertToApiResponse(serviceName: String)(response: WSResponse): ApiResponse = {
    if (response.status >= 400) {
      ApiError(response.status, s"There was a problem with the $serviceName. Service response:\n\n" + response.body)
    } else if (response.status >= 300) {
      ApiError(response.status, s"$serviceName seems to be configured incorrectly, received a redirect.")
    } else {
      ApiSuccess(response.body)
    }
  }

  /**
    * Executes the person linking rule and returns a set of sameAs links.
    *
    * @param content The RDF content as String.
    * @param acceptType An HTTP accept type that is used for serialization and deserialization from/to the REST
    *                   services.
    * @return
    */
  private def executePersonLinking(content: String,
                                   acceptType: String): Future[ApiResponse] = {
    val silkConfig = SilkConfig(
      projectId = ConfigFactory.load.getString("silk.socialApiProject.id"),
      linkingTaskId = ConfigFactory.load.getString("silk.linking.task.person"),
      silkServerUrl = ConfigFactory.load.getString("silk.server.url")
    )
    val entityLinking = new EntityLinking(silkConfig)
    val linkRequest = ws.url(silkConfig.endpoint)
      .withHeaders("Content-Type" -> "application/xml")
      .withHeaders("Accept" -> acceptType)
    linkRequest.post(entityLinking.linkTemplate(content, acceptTypeToRdfLang(acceptType)))
      .map(convertToApiResponse("Silk linking service"))
  }

  /** Merge all transformation results into a single model and return the serialized model */
  private def convertToRdf(lang: String,
                           futureResponses: Seq[Future[ApiResponse]]): Future[String] = {
    Future.sequence(futureResponses) map { responses =>
      val model = ModelFactory.createDefaultModel()
      responses.foreach {
        case ApiSuccess(body) =>
          model.add(rdfStringToModel(body, lang))
        case ApiError(statusCode, errorMessage) =>
          Logger.warn(s"Got status code $statusCode with message: $errorMessage")
      }
      modelToTripleString(model, "application/n-triples")
    }
  }

  /** Creates the complete API REST request and executes it asynchronously. */
  def createApiRequest(wrapper: RestApiWrapperTrait, query: String, page: Option[String], country: Option[String]): WSRequest = {
    val apiRequest: WSRequest = ws.url(wrapper.apiUrl)
    val apiRequestWithApiParams = addQueryParameters(wrapper, apiRequest, query, page: Option[String], country: Option[String])
    val apiRequestWithOAuthIfNeeded = handleOAuth(wrapper, apiRequestWithApiParams)
    apiRequestWithOAuthIfNeeded
  }

  /** Add all query parameters to the request. */
  private def addQueryParameters(wrapper: RestApiWrapperTrait,
                                 request: WSRequest,
                                 queryString: String,
                                 page: Option[String],
                                 country: Option[String]): WSRequest = {

    var url_with_params = wrapper.apiUrl+"?"

    if(wrapper.sourceLocalName.equals("adzuna")){
      var url_sb = new StringBuilder(wrapper.apiUrl)
      url_sb.setCharAt(34,country.get.charAt(0))
      url_sb.setCharAt(35,country.get.charAt(1))
      url_sb.append(page.get)
      url_with_params = url_sb.toString()+"?"
      }

    for ((k,v) <- wrapper.searchQueryAsParam(queryString)){
      url_with_params = url_with_params.concat(k+"="+v+"&")
    }

    for ((k,v) <- wrapper.queryParams){
      url_with_params = url_with_params.concat(k+"="+v+"&")
    }

    if(wrapper.sourceLocalName.equals("indeed")){
      url_with_params = url_with_params.concat("start="+ ((page.get.toInt - 1 ) * wrapper.max_results ) +"&")
      url_with_params = url_with_params.concat("co="+country.get+"&")
    }

    val apiRequest: WSRequest = ws.url(url_with_params.dropRight(1))
    val final_url = apiRequest.withHeaders(wrapper.headersParams.toSeq: _*)
    print(final_url.url)
    final_url
  }

  /** Signs the request if the [[RestApiOAuthTrait]] is configured. */
  private def handleOAuth(wrapper: RestApiWrapperTrait,
                          request: WSRequest): WSRequest = {
    wrapper match {
      case oAuthWrapper: RestApiOAuthTrait =>
        request
          .sign(OAuthCalculator(
            oAuthWrapper.oAuthConsumerKey,
            oAuthWrapper.oAuthRequestToken))

      case oAuth2Wrapper: RestApiOAuth2Trait =>
        request.withQueryString("access_token" -> oAuth2Wrapper.oAuth2AccessToken)
      case _ =>
        request
    }
  }

  // Return all wrapper ids as a JSON list
  def wrapperIds() = {
    Action {
      Ok(JsArray(WrapperController.sortedWrapperIds.map(id => JsString(id))))
    }
  }
}

/**
  * For now, hard code all available wrappers here. Later this should probably be replaced by a plugin mechanism.
  */
object WrapperController {
  val wrapperMap = Map[String, RestApiWrapperTrait](
    //Social Networks
    "gplus" -> new GooglePlusWrapper(),
    "twitter" -> new TwitterWrapper(),
    "facebook" -> new FacebookWrapper(),
    //Knowledge base
    "gkb" -> new GoogleKnowledgeGraphWrapper(),
    //eCommerce
    "ebay" -> new EBayWrapper(),
    //Darknet
    "tor2web" -> new Tor2WebWrapper(),
    //Linked leaks
    "linkedleaks" -> new LinkedLeaksWrapper(),
    //OCCRP
    "occrp" -> new OCCRPWrapper(),
    //Xing
    "xing" -> new XingWrapper(),
    //Elastic Search
    "elasticsearch" -> new ElasticSearchWrapper(),

    //EDSA WRAPPERS:

    //ADZUNA
    "adzuna" -> new AdzunaWrapper(),
    //INDEED
    "indeed" -> new IndeedWrapper(),
    //JOOBLE
    "jooble" -> new JoobleWrapper()
  )

  val sortedWrapperIds = wrapperMap.keys.toSeq.sortWith(_ < _)
}