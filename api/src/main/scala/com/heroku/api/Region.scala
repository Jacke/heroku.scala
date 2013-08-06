package com.heroku.api

import com.heroku.api.Request._

object Region {
  case class Info(id: String, extraHeaders: Map[String, String] = Map.empty) extends Request[Region] {
    val endpoint = s"/regions/$id"
    val expect = expect200
    val method = GET
  }

  case class List(range: Option[String] = None, extraHeaders: Map[String, String] = Map.empty) extends ListRequest[Region] {
    val endpoint = "/regions"
    val method = GET

    def nextRequest(nextRange: String): ListRequest[Region] = this.copy(range = Some(nextRange))
  }

}

case class Region(created_at: String, description: String, id: String, name: String, updated_at: String)

trait RegionResponseJson {
  implicit def regionListFromJson: FromJson[List[Region]]

  implicit def regionFromJson: FromJson[Region]
}