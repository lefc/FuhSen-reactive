package controllers.de.fuhsen.wrappers

import javax.inject.Inject

import com.typesafe.config.ConfigFactory
import org.apache.jena.rdf.model.Model
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.WSClient
import play.api.mvc.{Action, Controller}
import play.api.libs.concurrent.Execution.Implicits.defaultContext

/**
  * Created by dcollarana on 5/19/2017.
  */
class QMinerController @Inject()(ws: WSClient) extends Controller {

  def send = Action.async {
    val data = Json.parse(
      """
        |[{
        |		"date": "2017-10-02T05:24:32Z",
        |		"description": "... availability and consistency for all stakeholders. About you : Bachelor\u2019s / master\u2019s degree in a STEM discipline or relevant work experience in the field of scientific <strong>computing<\/strong>. Possess ...  <strong>advanced<\/strong> skills in SQL, PostgreSQL or Python. Well versed in relational databases, data and data analysis. Proficient in handling large data sets and ideally have experience in data ...",
        |		"title": "Data Warehouse Engineer Master Blaster",
        |		"uri": "eyJhbGciOiJIUzI1NiJ0.eyJzIjoiZUFzTmhhYkpSNXVsSTJjTUREazBFZyIsImkiOiI0NDMzNTA0NzcifQ.nq-m-g-hZ3u1CQP0Um3EV6cosjv0C3peVXlluV8hwxc",
        |		"url": "https://www.adzuna.de/land/ad/443350477?se=eAsNhabJR5ulI2cMDDk0Eg&utm_medium=api&utm_source=bf155b6a&v=E2048EFE2E9F3342709D855CEA5FED6917E9681A",
        |		"inLocation": {
        |			"name": "Seehof",
        |			"coord": [52.47122, 13.32833],
        |			"uri": "http://sws.geonames.org/2833755"
        |		},
        |		"inCountry": {
        |			"name": "Germany",
        |			"coord": [52.47122, 13.32833],
        |			"uri": "http://sws.geonames.org/2921044"
        |		},
        |		"foundIn": {
        |			"name": "Jooble"
        |		},
        |		"requiredSkills": [{
        |			"name": "data warehouse",
        |			"uri": "http://www.edsa-project.eu/skill/data warehouse"
        |		}],
        |		"forOrganization": {
        |			"title": "Blacklane GmbH"
        |		}
        |	},
        |	{
        |		"date": "2016-10-02T05:24:32Z",
        |		"description": "...  availability and consistency for all stakeholders. About you : Bachelor\u2019s / master\u2019s degree in a STEM discipline or relevant work experience in the field of scientific <strong>computing<\/strong>. Possess ...  <strong>advanced<\/strong> skills in SQL, PostgreSQL or Python. Well versed in relational databases, data and data analysis. Proficient in handling large data sets and ideally have experience in data ...",
        |		"title": "Data Warehouse Engineer (f/m)",
        |		"uri": "eyJhbGciOiJIUzI1NiJ9.eyJzIjoiZUFzTmhhYkpSNXVsSTJjTUREazBFZyIsImkiOiI0NDMzNTA0NzcifQ.nq-m-g-hZ3u1CQP0Um3EV6cosjv0C3peVXlluV8hwxc",
        |		"url": "https://www.adzuna.de/land/ad/443350477?se=eAsNhabJR5ulI2cMDDk0Eg&utm_medium=api&utm_source=bf155b6a&v=E2048EFE2E9F3342709D855CEA5FED6917E9681F",
        |		"inLocation": {
        |			"name": "Seehof",
        |			"coord": [52.47122, 13.32833],
        |			"uri": "http://sws.geonames.org/2833755"
        |		},
        |		"inCountry": {
        |			"name": "Germany",
        |			"coord": [52.47122, 13.32833],
        |			"uri": "http://sws.geonames.org/2921044"
        |		},
        |		"foundIn": {
        |			"name": "Adzuna"
        |		},
        |		"requiredSkills": [{
        |				"name": "data warehouse",
        |				"uri": "http://www.edsa-project.eu/skill/data warehouse"
        |			},
        |			{
        |				"name": "sql",
        |				"uri": "http://www.edsa-project.eu/skill/sql"
        |			},
        |			{
        |				"name": "postgresql",
        |				"uri": "http://www.edsa-project.eu/skill/postgresql"
        |			},
        |			{
        |				"name": "relational database",
        |				"uri": "http://www.edsa-project.eu/skill/relational database"
        |			}
        |		],
        |		"foundConcepts": [{
        |				"name": "Cloud Computing",
        |				"uri": "http://de.wikipedia.org/wiki/Cloud_Computing"
        |			},
        |			{
        |				"name": "Data-Warehouse",
        |				"uri": "http://de.wikipedia.org/wiki/Data-Warehouse"
        |			},
        |			{
        |				"name": "Warehouse",
        |				"uri": "http://de.wikipedia.org/wiki/Warehouse"
        |			},
        |			{
        |				"name": "Toningenieur",
        |				"uri": "http://de.wikipedia.org/wiki/Toningenieur"
        |			},
        |			{
        |				"name": "Klammer (Zeichen)",
        |				"uri": "http://de.wikipedia.org/wiki/Klammer_(Zeichen)"
        |			}
        |		],
        |		"forOrganization": {
        |			"title": "Blacklane GmbH"
        |		}
        |	}
        |]
      """.stripMargin
    )
    Logger.info("Json value sent")
    ws.url(ConfigFactory.load.getString("qminer.endpoint.url"))
      .post(data)
      .map( response =>
        if (response.status >= 300) {
          InternalServerError(s"${response.status} server error in the service")
        } else
          Ok
      )
  }

  def receive = Action { request =>
    Logger.info("Starting to receive the Json")
    val json = request.body.asJson
    json match {
      case Some(value) =>
        Logger.info("Json value received")
        Logger.info(value.toString)
        Ok
      case None => BadRequest("No Json Sent!!!")
    }
  }

  private def convert2Json(model: String): JsValue = {
    null
  }

}

