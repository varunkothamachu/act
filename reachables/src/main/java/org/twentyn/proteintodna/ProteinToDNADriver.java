/*************************************************************************
*                                                                        *
*  This file is part of the 20n/act project.                             *
*  20n/act enables DNA prediction for synthetic biology/bioengineering.  *
*  Copyright (C) 2017 20n Labs, Inc.                                     *
*                                                                        *
*  Please direct all queries to act@20n.com.                             *
*                                                                        *
*  This program is free software: you can redistribute it and/or modify  *
*  it under the terms of the GNU General Public License as published by  *
*  the Free Software Foundation, either version 3 of the License, or     *
*  (at your option) any later version.                                   *
*                                                                        *
*  This program is distributed in the hope that it will be useful,       *
*  but WITHOUT ANY WARRANTY; without even the implied warranty of        *
*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
*  GNU General Public License for more details.                          *
*                                                                        *
*  You should have received a copy of the GNU General Public License     *
*  along with this program.  If not, see <http://www.gnu.org/licenses/>. *
*                                                                        *
*************************************************************************/

package org.twentyn.proteintodna;

import act.server.MongoDB;
import act.shared.Chemical;
import act.shared.Reaction;
import act.shared.Seq;
import com.act.biointerpretation.metadata.ProteinMetadata;
import com.act.biointerpretation.metadata.RankPathway;
import com.act.reachables.Cascade;
import com.act.reachables.ReactionPath;
import com.act.utils.CLIUtil;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoInternalException;
import com.mongodb.ServerAddress;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mongojack.DBCursor;
import org.mongojack.DBQuery;
import org.mongojack.JacksonDBCollection;
import org.mongojack.WriteResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ProteinToDNADriver {

  private static final Logger LOGGER = LogManager.getFormatterLogger(ProteinToDNADriver.class);

  private static final String DEFAULT_DB_HOST = "localhost";
  private static final String DEFAULT_DB_PORT = "27017";
  private static final String DEFAULT_OUTPUT_DB_NAME = "wiki_reachables";
  private static final String DEFAULT_INPUT_DB_NAME = "SHOULD_COME_FROM_CMDLINE"; // "jarvis_2016-12-09";
  public static final String DEFAULT_INPUT_PATHWAY_COLLECTION_NAME = "SHOULD_COME_FROM_CMDLINE"; // "pathways_jarvis";
  public static final String DEFAULT_OUTPUT_PATHWAY_COLLECTION_NAME = "SHOULD_COME_FROM_CMDLINE"; // "pathways_vijay";
  public static final String DEFAULT_OUTPUT_DNA_SEQ_COLLECTION_NAME = "SHOULD_COME_FROM_CMDLINE"; // "dna_designs";

  private static final String OPTION_DB_HOST = "H";
  private static final String OPTION_DB_PORT = "p";
  private static final String OPTION_OUTPUT_DB_NAME = "o";
  private static final String OPTION_INPUT_DB_NAME = "i";
  private static final String OPTION_INPUT_PATHWAY_COLLECTION_NAME = "c";
  private static final String OPTION_OUTPUT_PATHWAY_COLLECTION_NAME = "d";
  private static final String OPTION_OUTPUT_DNA_SEQ_COLLECTION_NAME = "e";
  private static final String OPTION_DESIGN_SOME = "m";
  private static final Integer HIGHEST_SCORING_INFERRED_SEQ_INDEX = 0;
  private static final Set<String> BLACKLISTED_WORDS_IN_INFERRED_SEQ = new HashSet<>(Arrays.asList("Fragment"));

  private static final Pattern REGEX_ID = Pattern.compile("^\\d+$");
  private static final Pattern REGEX_INCHI = Pattern.compile("^InChI=1S?/");
  // Based on https://en.wikipedia.org/wiki/International_Chemical_Identifier#InChIKey
  private static final Pattern REGEX_INCHI_KEY = Pattern.compile("^[A-Z]{14}-[A-Z]{10}-[A-Z]$");

  public static final List<Option.Builder> OPTION_BUILDERS = new ArrayList<Option.Builder>() {{
    add(Option.builder(OPTION_DB_HOST)
        .argName("hostname")
        .desc(String.format("The DB host to which to connect (default: %s)", DEFAULT_DB_HOST))
        .hasArg()
        .longOpt("db-host")
    );
    add(Option.builder(OPTION_DB_PORT)
        .argName("port")
        .desc(String.format("The DB port to which to connect (default: %s)", DEFAULT_DB_PORT))
        .hasArg()
        .longOpt("db-port")
    );
    add(Option.builder(OPTION_OUTPUT_DB_NAME)
        .argName("output-db-name")
        .desc(String.format("The name of the database to read pathways and write seqs to (default: %s)", DEFAULT_OUTPUT_DB_NAME))
        .hasArg()
        .longOpt("output-db-name")
    );
    add(Option.builder(OPTION_INPUT_DB_NAME)
        .argName("input-db-name")
        .desc(String.format("The name of the database to read reactions from (default: %s)", DEFAULT_INPUT_DB_NAME))
        .hasArg()
        .required()
        .longOpt("source-db-name")
    );
    add(Option.builder(OPTION_INPUT_PATHWAY_COLLECTION_NAME)
        .argName("collection-name")
        .desc(String.format("The name of the input pathway collection to read from (default: %s)", DEFAULT_INPUT_PATHWAY_COLLECTION_NAME))
        .hasArg()
        .required()
        .longOpt("input-pathway-collection")
    );
    add(Option.builder(OPTION_OUTPUT_PATHWAY_COLLECTION_NAME)
        .argName("collection-name")
        .desc(String.format("The name of the output pathway collection to write to (default: %s)", DEFAULT_OUTPUT_PATHWAY_COLLECTION_NAME))
        .hasArg()
        .required()
        .longOpt("output-pathway-collection")
    );
    add(Option.builder(OPTION_OUTPUT_DNA_SEQ_COLLECTION_NAME)
        .argName("collection-name")
        .desc(String.format("The name of the output dna seq collection to write to (default: %s)", DEFAULT_OUTPUT_DNA_SEQ_COLLECTION_NAME))
        .hasArg()
        .required()
        .longOpt("output-dna-seq-collection")
    );
    add(Option.builder(OPTION_DESIGN_SOME)
        .argName("molecule")
        .desc("Generate designs for specified molecules; can be a numeric ids, InChIs, or InChI Keys, *separated by '|'* " +
            "for InChI compatibility.  Molecules must exist in chemical source DB if InChI or InChI Key is used.")
        .hasArgs().valueSeparator('|')
        .longOpt("design-this")
    );
  }};

  public static final String HELP_MESSAGE =
      "This class is the driver to extract protein sequences from pathways and construct DNA designs from these proteins.";

  private static final CLIUtil CLI_UTIL = new CLIUtil(ProteinToDNADriver.class, HELP_MESSAGE, OPTION_BUILDERS);

  /**
   * This function gets all protein combinations of a pathway from candidate protein sequences from each reaction on
   * the pathway.
   * @param listOfSetOfProteinSequences A list of sets of candidate protein sequences in the pathway
   * @return A set of all possible combinations of proteins from all the reactions in the pathway.
   */
  public static Set<List<String>> makePermutations(List<Set<String>> listOfSetOfProteinSequences) {
    Set<List<String>> accum = new HashSet<>();
    makePermutationsHelper(accum, listOfSetOfProteinSequences, new ArrayList<>());
    return accum;
  }

  private static void makePermutationsHelper(Set<List<String>> accum, List<Set<String>> input, List<String> prefix) {
    // Base case: no more sequences to add.  Accumulate and return.
    if (input.isEmpty()) {
      accum.add(prefix);
      return;
    }

    // Recursive case: iterate through next level of input sequences, appending each to a prefix and recurring.
    Set<String> head = input.get(0);
    // Avoid index out of bounds exception.
    List<Set<String>> rest = input.size() > 1 ? input.subList(1, input.size()) : Collections.emptyList();
    for (String next : head) {
      List<String> newPrefix = new ArrayList<>(prefix);
      newPrefix.add(next);
      makePermutationsHelper(accum, rest, newPrefix);
    }
  }

  public static void main(String[] args) throws Exception {
    CommandLine cl = CLI_UTIL.parseCommandLine(args);

    String reactionDbName = cl.getOptionValue(OPTION_INPUT_DB_NAME, DEFAULT_INPUT_DB_NAME);
    String dbHost = cl.getOptionValue(OPTION_DB_HOST, DEFAULT_DB_HOST);
    Integer dbPort = Integer.valueOf(cl.getOptionValue(OPTION_DB_PORT, DEFAULT_DB_PORT));
    MongoDB reactionDB = new MongoDB(dbHost, dbPort, reactionDbName);

    MongoClient inputClient = new MongoClient(new ServerAddress(dbHost, dbPort));
    String outDB = cl.getOptionValue(OPTION_OUTPUT_DB_NAME, DEFAULT_OUTPUT_DB_NAME);
    DB db = inputClient.getDB(outDB);

    String inputPathwaysCollectionName = cl.getOptionValue(OPTION_INPUT_PATHWAY_COLLECTION_NAME, DEFAULT_INPUT_PATHWAY_COLLECTION_NAME);
    String outputPathwaysCollectionName = cl.getOptionValue(OPTION_OUTPUT_PATHWAY_COLLECTION_NAME, DEFAULT_OUTPUT_PATHWAY_COLLECTION_NAME);
    String outputDnaDeqCollectionName = cl.getOptionValue(OPTION_OUTPUT_DNA_SEQ_COLLECTION_NAME, DEFAULT_OUTPUT_DNA_SEQ_COLLECTION_NAME);

    JacksonDBCollection<ReactionPath, String> inputPathwayCollection = JacksonDBCollection.wrap(db.getCollection(inputPathwaysCollectionName), ReactionPath.class, String.class);
    JacksonDBCollection<DNADesign, String> dnaDesignCollection = JacksonDBCollection.wrap(db.getCollection(outputDnaDeqCollectionName), DNADesign.class, String.class);
    JacksonDBCollection<ReactionPath, String> outputPathwayCollection = JacksonDBCollection.wrap(db.getCollection(outputPathwaysCollectionName), ReactionPath.class, String.class);

    Map<String, Set<ProteinInformation>> proteinSeqToOrgInfo = new HashMap<>();

    ProteinsToDNA2 p2d = ProteinsToDNA2.initiate();

    List<Long> reachableIds = cl.hasOption(OPTION_DESIGN_SOME) ?
        Arrays.stream(cl.getOptionValues(OPTION_DESIGN_SOME)).
            map(m -> lookupMolecule(reactionDB, m)).collect(Collectors.toList()) :
        Collections.emptyList();

    // If there is a list of molecules for which to create designs, only consider their pathways.  Otherwise, find all.
    DBObject query = reachableIds.isEmpty() ? new BasicDBObject() : DBQuery.in("target", reachableIds);

    /* Extract all pathway ids, then read pathways one id at a time.  This will reduce the likelihood of the cursor
     * timing out, which has a tendency to happen when doing expensive operations before advancing (as done here). */
    DBCursor<ReactionPath> cursor = inputPathwayCollection.find(query, new BasicDBObject("_id", true));
    List<String> pathwayIds = new ArrayList<>();
    while (cursor.hasNext()) {
      pathwayIds.add(cursor.next().getId());
    }
    
    for (String pathwayId : pathwayIds) {
      ReactionPath reactionPath = inputPathwayCollection.findOne(DBQuery.is("_id", pathwayId));

      List<List<Pair<ProteinMetadata, Integer>>> processedP = RankPathway.processSinglePathAsJava(reactionPath, reactionDbName, outDB);
      if (processedP == null) {
        LOGGER.info(String.format("Process pathway was filtered out possibly because there were more than %s seqs for a given pathway",
            RankPathway.MAX_PROTEINS_PER_PATH()));
        outputPathwayCollection.insert(reactionPath);
        continue;
      }

      Boolean atleastOneSeqMissingInPathway = false;
      List<Set<String>> proteinPaths = new ArrayList<>();

      for (Cascade.NodeInformation nodeInformation :
          reactionPath.getPath().stream().filter(nodeInfo -> nodeInfo.getIsReaction()).collect(Collectors.toList())) {

        Set<String> proteinSeqs = new HashSet<>();

        for (Long id : nodeInformation.getReactionIds()) {

          // If the id is negative, it is a reaction in the reverse direction. Moreover, the enzyme for this reverse
          // reaction is the same, so can use the actual positive reaction id's protein seq reference.
          // TODO: Add a preference for the positive forward direction compared to the negative backward direction seq.
          if (id < 0) {
            LOGGER.info("Found a negative reaction id", id);
            id = Reaction.reverseID(id);
          }

          Reaction reaction = reactionDB.getReactionFromUUID(id);

          for (JSONObject data : reaction.getProteinData()) {
            // Get the sequences
            if (data.has("sequences")) {
              JSONArray seqs = data.getJSONArray("sequences");

              for (int i = 0; i < seqs.length(); i++) {
                Long s = seqs.getLong(i);

                if (s != null) {
                  Seq sequenceInfo = reactionDB.getSeqFromID(s);
                  String dnaSeq = sequenceInfo.getSequence();

                  if (dnaSeq == null) {
                    LOGGER.info(String.format("Sequence string for seq id %d, reaction id %d and reaction path %s is null",
                        s, id, reactionPath.getId()));
                    continue;
                  }

                  // odd sequence
                  if (dnaSeq.length() <= 80 || dnaSeq.charAt(0) != 'M') {
                    JSONObject metadata = sequenceInfo.getMetadata();

                    if (!metadata.has("inferred_sequences") || metadata.getJSONArray("inferred_sequences").length() == 0) {
                      continue;
                    }

                    JSONArray inferredSequences = metadata.getJSONArray("inferred_sequences");

                    // get the first inferred sequence since it has the highest hmmer score
                    JSONObject object = inferredSequences.getJSONObject(HIGHEST_SCORING_INFERRED_SEQ_INDEX);

                    for (String blacklistWord : BLACKLISTED_WORDS_IN_INFERRED_SEQ) {
                      if (object.getString("fasta_header").contains(blacklistWord)) {
                        continue;
                      }
                    }

                    dnaSeq = object.getString("sequence");
                  }

                  proteinSeqs.add(dnaSeq);
                  ProteinInformation proteinInformation = new ProteinInformation(sequenceInfo.getOrgName(), sequenceInfo.getEc(),
                      sequenceInfo.getSequence(), reaction.getReactionName());

                  if (!proteinSeqToOrgInfo.containsKey(dnaSeq)) {
                    proteinSeqToOrgInfo.put(dnaSeq, new HashSet<>());
                  }
                  proteinSeqToOrgInfo.get(dnaSeq).add(proteinInformation);
                }
              }
            }
          }
        }

        if (proteinSeqs.size() == 0) {
          LOGGER.info("The reaction does not have any viable protein sequences");
          atleastOneSeqMissingInPathway = true;
          break;
        }

        // Now we select two representative protein seqs from the reaction. In order to do this deterministically,
        // we sort and pick the first and middle index protein seqs.
        List<String> proteinSeqArray = new ArrayList<>(proteinSeqs);
        Collections.sort(proteinSeqArray);

        int firstIndex = 0;
        int middleIndex = proteinSeqs.size() / 2;

        // get first seq
        Set<String> combination = new HashSet<>();
        combination.add(proteinSeqArray.get(firstIndex));

        // get middle index of the protein seq array
        if (proteinSeqs.size() > 1) {
          combination.add(proteinSeqArray.get(middleIndex));
        }

        proteinPaths.add(combination);
      }

      if (atleastOneSeqMissingInPathway) {
        LOGGER.info(String.format("There is at least one reaction with no sequence in reaction path id: %s", reactionPath.getId()));
      } else {
        LOGGER.info(String.format("All reactions in reaction path have at least one viable seq: %s", reactionPath.getId()));

        // We only compute the dna design if we can find at least one sequence for each reaction in the pathway.
        Set<List<String>> pathwayProteinCombinations = makePermutations(proteinPaths);
        Set<DNAOrgECNum> dnaDesigns = new HashSet<>();

        for (List<String> proteinsInPathway : pathwayProteinCombinations) {
          try {
            Construct dna = p2d.computeDNA(proteinsInPathway, Host.Ecoli);

            List<Set<ProteinInformation>> seqMetadata = new ArrayList<>();
            for (String protein : proteinsInPathway) {
              seqMetadata.add(proteinSeqToOrgInfo.get(protein));
            }

            DNAOrgECNum instance = new DNAOrgECNum(dna.toSeq(), seqMetadata, proteinsInPathway.size());
            dnaDesigns.add(instance);
          } catch (Exception ex) {
            LOGGER.error("The error thrown while trying to call computeDNA", ex.getMessage());
          }
        }

        DNADesign dnaDesignSeq = new DNADesign(dnaDesigns);
        try {
          WriteResult<DNADesign, String> result = dnaDesignCollection.insert(dnaDesignSeq);
          String id = result.getSavedId();
          reactionPath.setDnaDesignRef(id);
        } catch (MongoInternalException e) {
          // This condition happens whent the protein designs are too big and cannot be inserted in to the JSON object.
          // TODO: Handle this case without dropping the record
          LOGGER.error(String.format("Mongo internal exception caught while inserting dna design: %s", e.getMessage()));
        }

      }

      outputPathwayCollection.insert(reactionPath);
    }
  }

  private static Long lookupMolecule(MongoDB db, String someKey) {
    if (REGEX_ID.matcher(someKey).find()) {
      // Note: this doesn't verify that the chemical id is valid.  Maybe we should do that?
      return Long.valueOf(someKey);
    } else if (REGEX_INCHI.matcher(someKey).find()) {
      Chemical c = db.getChemicalFromInChI(someKey);
      if (c != null) {
        return c.getUuid();
      }
    } else if (REGEX_INCHI_KEY.matcher(someKey).find()) {
      Chemical c = db.getChemicalFromInChIKey(someKey);
      if (c != null) {
        return c.getUuid();
      }
    } else {
      String msg = String.format("Unable to find key type for query '%s'", someKey);
      LOGGER.error(msg);
      throw new IllegalArgumentException(msg);
    }
    String msg = String.format("Unable to find matching chemical for query %s", someKey);
    LOGGER.error(msg);
    throw new IllegalArgumentException(msg);
  }
}
