# PROOF2 Importer

The PROOF2 Importer is designed as a spring-boot application (command line) to import PROOF2 models to the database. The PROOF2 Models are defined in JSON files and in referenced code files (Python, Java) in the same directory.
It can also be used to save own models under development. The repository contains final sample models for PROOF2 as well as test models to test the import features.

All models are checked for consistency:

- are all blocks available either in the import dierctories or are they already in the database?
- are there duplicate defined blocks and workflows (same block or workflow name inside different files)?


## Installation

```bash
git clone  https://git.scc.kit.edu/IAI/Private/webis/webis-proof2/proof-importer.git
cd proof-importer
mvn clean compile package
```

## Start

Simply start the jar file with 'mvn spring-boot:run' using some (optional) arguments divided by whitespaces and enclosed with one quote pair:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--printDBContents=true --overwriteDBContents=true --save=false --useSubDirs=false --importPath=<pathToImportDirectory> --spring.profiles.active=development"
```
Possible arguments are:

- spring.profiles.active:<br /> (optional) selection of a desired profile. To test the given development environment, use 'development' as value (see src/main/resoures/application-development.properties). To use an own profile, use the separate maven argument '-Dspring.config.location=<pathToMyConfigFile>' (mvn spring-boot:run -Dspring.config.location=<pathToMyConfigFile> -Dspring-boot.run.arguments=...). Without this argument, the default profile is used (see src/main/resoures/application.properties)
- save:<br /> (optional, default is 'false') if save=true, the PROOF2 elements are stored to the PROOF2 database (Elasticsearch instance), if false, the import can be tested with respect to only check consistency. This argument must be set explicitly to avoid data loss.
- overwriteDBContents:<br /> (optional, default is 'false') if overwriteDBContents=true, the elements which already exist in the database are overridden. This feature is used to update model definitions.
- printDBContents:<br /> (optional, default is 'false') if printDBContents=true, the already existing models of the databased are printed by name. This feature allows to look, whether a model is already stored in the database without the need to use a separate UI. Default is 'false'
- useSubDirs:<br /> (optional, default is 'false') if useSubDirs=true, all sub directories of the given parent directory will be scanned for configuration files.
- importPath or workflow:  
-    - importPath: the path to the directory with the JSON and Code files. The path is mandatory for import and consistency checks, but not for only showing the contents of the database. 
-    - workflow: the absolute file name (including path) of a workflow configuration file. This allows the import of one single workflow and ignore all other ones.
<br />Note: as the blocks and programs are referenced by name (UUID) and not by file name, all configuration files of the workflow 'environment' (i.e., the workflow directory) will be imported to get the referenced elements.
## Examples

### Test the Import of own PROOF2 Model Files

```bash

     mvn spring-boot:run -Dspring-boot.run.arguments="--overwriteDBContents=false --save=false --importPath=/home/sth/myModels"
```
This means:

- do not save the elements, i.e. only test the import (--save=false) for files in the given directory '/home/sth/myModels'
- do not overwrite existing elements in the database
- do not show the contents of the database

```bash

     mvn spring-boot:run -Dspring-boot.run.arguments="--importPath=/home/sth/myModels"
```
This means:  

- do not save the elements, i.e. only test the import (--save=false) for files in the directory '/home/sth/myModels'
- do not overwrite existing elements in the database
- do not show the contents of the database

# Import PROOF2 Base Models and Examples

The current bas models and example models of PROOF2 are located in the directory 'proofmodels' of the project. To import the models, use the command above with the following arguments:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--printDBContents=true --overwriteDBContents=true --save=true --importPath=<projectPath>/proofmodels" -Dspring.config.location=<pathToMyConfigFile>
```
- use an own application.properties file with individual settings, e.g. the acces data to the Elasticsearch database etc.
- alternatively the default application.properties file of the project (src/main/reseources/application.properties) can be adapted to the personal needs. Note: a new (git-)pulling of a newer importer version may then lead to merge conflicts!

Note that all existing PROOF2 base models and examples will be overridden in the database. In this way, the models can be updatet after having (git-)pulled the newest version of the importer.

# Show Database Contents

It is possible to only show the database contents (Workflows, Blocks, and Programs) if only the argument "--printDBContents=true" is used. The desired database URL is set in the properties files (application.properties, application-development.properties, etc.) with the key "spring.elasticsearch.uris=http...." 

# Evaluate Test Models

To ensure the correct working of the importer, some test models are provided with injected errors. They can be found in the project (exact location: tdb)

