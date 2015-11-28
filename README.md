crowd-pulse-infogram
====================

Crowd Pulse info-graphics generator.

--------------------

The `infogram` plugin needs a `infogram.properties` file in the class loader accessible resources 
directory.
The file must contain the following properties:

- `infogram.apikey` is the API key for your Infogram account
- `infogram.secret` is your secret key for your Infogram account

The configuration for the plugin can specify a `path` where the generated infographs must be saved 
as PNGs. If no `path` is set, the files will be saved into the system temporary directory.
  
## License

```
  Copyright 2015 Francesco Pontillo

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.

```