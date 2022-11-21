# Load Runner by Kotlin

## Usage
- With random integer parameter  
Below case is to random from 1 to 1 million, generated randomly as an userId.
```
java -jar my.jar -n 10000 -c 1000 http://localhost:8080/users/{int(1,1000000)}
```