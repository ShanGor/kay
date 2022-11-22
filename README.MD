# Load Runner by Kotlin

## Usage
- With random integer parameter in the URL  
  Below case is to generate random number from 1 to 1 million, generated randomly as an userId.
  ```
  java -jar my.jar -n 10000 -c 1000 http://localhost:8080/users/{int(1,1000000)}
  ```
- With random uuid in the URL  
  (Please do not use both random uuid and random int in the url)
  ```
  java -jar my.jar -n 10000 -c 1000 http://localhost:8080/reports/{uuid()}
  ```

## TODO
- to support load parameters from a file.  
  For example, below case the `ids.txt` is a text file, each line is an id number, in random manner.
  ```
  pgm -n <number> -c <concurrency> http://localhost:8080/reports/{@ids.txt}
  ```
- to support http methods
- to support -d "my data" format
- to support -d @file format, will load the data to post
- to support `-d @file -l true` format, will loop the file to produce requests  
  If the total number of counts not equal to the row count, trust total number as `-n <number>`. Unless you set `-n 0`, then will set total number as the total line count of the file.