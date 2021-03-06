/**
 *  Copyright 2015 DBpedia Spotlight
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */


package org.dbpedia.spotlight.wikistats

import java.util.Locale

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.{Text, LongWritable}
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.spark.storage.StorageLevel
import org.dbpedia.spotlight.db.model.Stemmer
import org.dbpedia.spotlight.db.tokenize.LanguageIndependentStringTokenizer
import org.dbpedia.spotlight.model._
import org.dbpedia.spotlight.wikistats.utils._
import org.dbpedia.spotlight.wikistats.wikiformat.XmlInputFormat
import scala.collection.JavaConversions._
import scala.collection.mutable.HashMap

/*
Class to Parse the Raw WikiPedia dump into individual JSON format articles
Member variables -  1. Input Wikipedia Dump Path
                    2. Language of the Wikipedia dump
 */


class JsonPediaParser(inputWikiDump: String,
                      lang: String,
                      jsonParsing: Boolean)
                     (implicit val sc: SparkContext, implicit val sqlContext: SQLContext)
  extends WikiPediaParser{


  val pageRDDs = if (jsonParsing) parse(inputWikiDump).persist(StorageLevel.DISK_ONLY)
                 else sc.textFile(inputWikiDump)
  val dfWikiRDD = parseJSON(pageRDDs)

  /*
    Method to Begin the Parsing Logic
    @param:  - RDD of Individual article in JSON format
    @return: - Dataframe of the input RDD
   */

  def parseJSON(pageRDDs: RDD[String]): DataFrame ={

    //Create Initial DataFrame by Parsing using JSONRDD. This is from Spark 1.3 onwards
    sqlContext.read.json(pageRDDs)

  }

  /*
    Method to parse the XML dump into JSON
    @param:  - Path of the Wikipedia dump
    @return: - RDD of Individual article in JSON format
 */

  def parse(path: String): RDD[String] = {

    val conf = new Configuration()

    //Setting all the Configuration parameters to be used during XML Parsing
    conf.set(XmlInputFormat.START_TAG_KEY, "<page>")
    conf.set(XmlInputFormat.END_TAG_KEY, "</page>")
    conf.set(XmlInputFormat.LANG,lang)
    conf.set("mapreduce.input.fileinputformat.split.maxsize", "200000000")
    conf.set("mapreduce.input.fileinputformat.split.minsize", "199999999")

    val rawXmls = sc.newAPIHadoopFile(path, classOf[XmlInputFormat], classOf[LongWritable],
      classOf[Text], conf)

    rawXmls.map(p => p._2.toString)
  }

  /*
    Get Redirects from the wiki dump
    @param:   - Base dataframe consiting of wiki data
    @return:  - RDD with Redirect source and target
   */

  def redirectsWikiArticles(): RDD[(String, String)] = {

    dfWikiRDD.select("wikiTitle","type","redirect")
      .rdd
      .filter(row => row.getString(1)== "REDIRECT")
      .map(row => (row.getString(0),row.getString(2)))

  }

  /*
    Method to resolve transitive dependencies for the redirects.
    @param:  - None
    @return: - RDD with the resolved wiki uri and surface form
   */

  def getResolveRedirects(): RDD[(String, String)] = {

    val rddRedirects = redirectsWikiArticles()
    var linkMap = sc.accumulableCollection(HashMap[String, String]())

    rddRedirects.foreach(row => {linkMap += (row._1 -> row._2)})

    val mapBc = sc.broadcast(linkMap.value)


    rddRedirects.mapPartitions(rows => {
      val redirectUtil = new RedirectUtil(mapBc.value)
      rows.map { row => (redirectUtil.getEndOfChainURI(row._1), row._1)
      }})

  }

  /*
  Construct the Redirects HashMap for resolving the redirects
   */

  def constructResolvedRedirects(): RDD[(String, String)] = {

    val rddRedirects = redirectsWikiArticles()
    var linkMap = sc.accumulableCollection(HashMap[String, String]())

    rddRedirects.foreach(row => {linkMap += (row._1 -> row._2)})

    val mapBc = sc.broadcast(linkMap.value)

    rddRedirects.mapPartitions(rows => {
      val redirectUtil = new RedirectUtil(mapBc.value)
      rows.map { row => (row._1 , redirectUtil.getEndOfChainURI(row._1))
      }})
  }
  /*
     Method to Get the list of Surface forms from the wiki
    @param:  - None
    @return: - RDD of all Surface forms from the wikipedia dump
   */

  def getSfs() : RDD[String] = {

    import sqlContext.implicits._
    dfWikiRDD.select("wid","links.description","type")
      .rdd
      .filter(row => row.getString(2)== "ARTICLE")
      .map(artRow => artRow.getList[String](1))
      .flatMap(sf => sf)
      .toDF("sf")
      .select("sf")
      .distinct
      .rdd
      .map(row => row.getString(0))
  }

  /*
  Method to get the wid and article text from the wiki dump
    @param:  - None
    @return: - RDD of (wikiId, Article Text, List of Surface Form Occurrence for Spotter)
   */

  def getArticleText(): RDD[(Long, String, List[(SurfaceFormOccurrence, String, String)])] = {


    dfWikiRDD.select("wid","wikiText","type","links")
      .rdd
      .filter(row => row.getString(2) == "ARTICLE" )
      .filter(row => row.getString(1).length > 0)
      .map{row => ArticleRow(row.getLong(0),
      row.getString(1),
      row.getString(2),
      row.getAs[Seq[Row]](3).map(r => Span(r.getString(0),r.getLong(3),r.getString(2),r.getLong(3))))
    }
      .map(r => {val articleText = new org.dbpedia.spotlight.model.Text(r.wikiText)

      (r.wid,r.wikiText,r.spans.map{s =>
        val spot = new SurfaceFormOccurrence(new SurfaceForm(s.desc),
          articleText,
          s.start.toInt,
          Provenance.Annotation,
          -1)
        spot.setFeature(new Nominal("spot_type", "real"))
        (spot, s.desc, s.id)}.toList)})
  }

  /*
   Logic to Get Surface Forms and URIs from the wikiDump
    @param:  - None
    @return: - RDD of wiki-id, surface forms and uri
   */

  def getSfURI(): RDD[(Long, String, String)]= {

    dfWikiRDD.select(new Column("wid"),new Column("type"),explode( new Column("links")).as("link"))
      .select("type","wid","link.description","link.end","link.id","link.start")
      .rdd
      .filter(row => row.getString(0)== "ARTICLE")
      .filter(row => !(row.getLong(3)==0 && row.getLong(5)==0))
      .map(row => (row.getLong(1),row.getString(2),row.getString(4)))

  }

  /*
   Logic to get Links and paragraph text as RDD
    @param:  - None
    @return: - RDD with the paragraph links and the text
   */

  def getUriParagraphs(): RDD[(String, String)] = {

    import org.apache.spark.sql.functions._

    dfWikiRDD.select(explode( new Column("paragraphsLink")).as("paraLink"))
      .select(explode(new Column("paraLink.links.id")).as("id"),new Column("paraLink.paraText").as("para"))
      .distinct
      .rdd
      .map(row => (row.getString(0),row.getString(1)))
      .reduceByKey(SpotlightUtils.stringConcat)

  }

  /*
   Logic to get the list of all the tokens in the Surface forms
    @param:  - None
    @return: - List of different token types
   */

  def getTokensInSfs(allSfs: List[String]): List[TokenType] ={

    val stemmer = new Stemmer()
    val locale = new Locale(lang)
    val lst = new LanguageIndependentStringTokenizer(locale, stemmer)
    //Below Logic is for creating Token Store from the Surface forms
    //TODO Getting Searlization error Hence Using Collect and ToList. May need to change in Future
    val token = allSfs.flatMap( sf => lst.tokenizeUnstemmed(sf) )


    val tokenTypes=token.zip(Stream from 1).map{case (x,i) => new TokenType(i,x,0)}

    tokenTypes
  }


}